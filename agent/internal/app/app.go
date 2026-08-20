package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"sync"
	"time"

	"github.com/xxh3898/homeops/agent/internal/collector"
	"github.com/xxh3898/homeops/agent/internal/config"
	"github.com/xxh3898/homeops/agent/internal/containercontrol"
	"github.com/xxh3898/homeops/agent/internal/containerlog"
	"github.com/xxh3898/homeops/agent/internal/docker"
	"github.com/xxh3898/homeops/agent/internal/snapshot"
	"github.com/xxh3898/homeops/agent/internal/spool"
	"github.com/xxh3898/homeops/agent/internal/transport"
)

type App struct {
	config           config.Config
	version          string
	host             hostCollector
	docker           dockerCollector
	transport        snapshotTransport
	logReader        containerLogReader
	logTransport     containerLogTransport
	controlExecutor  containerControlExecutor
	controlTransport containerControlTransport
	spool            snapshotSpool
	logger           *slog.Logger
	now              func() time.Time
	wait             func(context.Context, time.Duration) bool
}

type hostCollector interface {
	Collect(context.Context) (snapshot.Host, error)
}

type dockerCollector interface {
	Containers(context.Context, int) ([]snapshot.Container, error)
}

type snapshotTransport interface {
	Send(context.Context, []byte) error
}

type containerLogReader interface {
	ContainerLogs(
		context.Context,
		string,
		int,
		int,
		time.Time,
	) (containerlog.Output, error)
}

type containerLogTransport interface {
	NextContainerLogWork(context.Context) (*containerlog.Work, error)
	SendContainerLogResult(context.Context, containerlog.Result) error
}

type containerControlExecutor interface {
	ControlContainer(
		context.Context,
		string,
		string,
		containercontrol.Operation,
		int,
		time.Time,
	) containercontrol.Outcome
}

type containerControlTransport interface {
	NextContainerControlWork(context.Context) (*containercontrol.Work, error)
	SendContainerControlResult(context.Context, containercontrol.Result) error
}

type snapshotSpool interface {
	Drain(func([]byte) error) (spool.DrainResult, error)
	Store(string, []byte) error
}

const collectionTimeout = 20 * time.Second

const (
	unsupportedLogAPIDelay         = 30 * time.Second
	transientLogErrorDelay         = time.Second
	emptyLogPollDelay              = 250 * time.Millisecond
	initialResultRetryDelay        = 100 * time.Millisecond
	maximumResultRetryDelay        = 500 * time.Millisecond
	unsupportedControlAPIDelay     = 30 * time.Second
	transientControlErrorDelay     = time.Second
	emptyControlPollDelay          = 250 * time.Millisecond
	initialControlResultRetryDelay = 100 * time.Millisecond
	maximumControlResultRetryDelay = 500 * time.Millisecond
)

var fullSHA = regexp.MustCompile(`^[0-9a-f]{40}$`)

func New(
	config config.Config,
	version string,
	logger *slog.Logger,
) (*App, error) {
	dockerClient, err := docker.NewClient(config.DockerSocket)
	if err != nil {
		return nil, err
	}
	transportClient, err := transport.NewClient(
		config.APIURL,
		config.ClientCert,
		config.ClientKey,
		config.CACert)
	if err != nil {
		return nil, err
	}
	spoolStore, err := spool.New(config.SpoolDir, config.MaxSpoolFiles)
	if err != nil {
		return nil, err
	}
	return &App{
		config:           config,
		version:          version,
		host:             collector.NewHostCollector(collector.ExecRunner{}),
		docker:           dockerClient,
		transport:        transportClient,
		logReader:        dockerClient,
		logTransport:     transportClient,
		controlExecutor:  dockerClient,
		controlTransport: transportClient,
		spool:            spoolStore,
		logger:           logger,
		now:              func() time.Time { return time.Now().UTC() },
		wait:             waitFor,
	}, nil
}

func (app *App) Run(ctx context.Context) error {
	var workers sync.WaitGroup
	if app.logReader != nil && app.logTransport != nil {
		workers.Add(1)
		go func() {
			defer workers.Done()
			app.runContainerLogWorker(ctx)
		}()
	}
	if app.controlExecutor != nil && app.controlTransport != nil {
		workers.Add(1)
		go func() {
			defer workers.Done()
			app.runContainerControlWorker(ctx)
		}()
	}
	err := app.runSnapshotLoop(ctx)
	workers.Wait()
	return err
}

func (app *App) runSnapshotLoop(ctx context.Context) error {
	if err := app.collectAndSend(ctx); err != nil {
		app.logger.Warn("initial snapshot failed", "error", err)
	}
	ticker := time.NewTicker(app.config.Interval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			if err := app.collectAndSend(ctx); err != nil {
				app.logger.Warn("snapshot failed", "error", err)
			}
		}
	}
}

func (app *App) collectAndSend(ctx context.Context) error {
	drainResult, err := app.spool.Drain(func(payload []byte) error {
		return app.transport.Send(ctx, payload)
	})
	if drainResult.Rejected > 0 {
		app.logger.Warn(
			"permanently rejected snapshots quarantined",
			"rejected_count",
			drainResult.Rejected,
			"delivered_count",
			drainResult.Delivered)
	}
	if err != nil {
		app.logger.Info("pending snapshots remain queued")
		return fmt.Errorf("drain pending snapshots: %w", err)
	}

	collectionContext, cancelCollection := context.WithTimeout(
		ctx,
		collectionTimeout)
	defer cancelCollection()
	host, err := app.host.Collect(collectionContext)
	if err != nil {
		return fmt.Errorf("collect host snapshot: %w", err)
	}
	containers, err := app.docker.Containers(
		collectionContext,
		app.config.MaxContainers)
	if err != nil {
		return fmt.Errorf("collect Docker snapshot: %w", err)
	}
	cancelCollection()
	snapshotID, err := newUUID()
	if err != nil {
		return err
	}
	now := app.currentTime().Truncate(time.Microsecond)
	payload, err := json.Marshal(snapshot.Snapshot{
		SnapshotID:            snapshotID,
		AgentID:               app.config.AgentID,
		AgentVersion:          app.version,
		CapturedAt:            now,
		SupportsContainerLogs: true,
		Host:                  host,
		Containers:            containers,
	})
	if err != nil {
		return fmt.Errorf("encode Agent snapshot: %w", err)
	}
	if err := app.transport.Send(ctx, payload); err == nil {
		if err := writeVersionProof(
			app.config.VersionProof,
			app.version,
			now,
		); err != nil {
			return err
		}
		return nil
	}
	spoolName := now.Format("20060102T150405000000000Z") + "-" + snapshotID
	if err := app.spool.Store(spoolName, payload); err != nil {
		return fmt.Errorf("queue undelivered snapshot: %w", err)
	}
	return errorsSentinel{}
}

func (app *App) runContainerLogWorker(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		work, err := app.logTransport.NextContainerLogWork(ctx)
		if err != nil {
			app.logger.Info("container log work poll unavailable")
			if !app.waitFor(ctx, containerLogBackoff(err)) {
				return
			}
			continue
		}
		if work == nil {
			if !app.waitFor(ctx, emptyLogPollDelay) {
				return
			}
			continue
		}
		result := app.executeContainerLogWork(ctx, *work)
		delivery := app.deliverContainerLogResult(
			ctx,
			&result,
			work.ExpiresAt)
		if delivery == resultDeliveryCancelled {
			return
		}
		if delivery == resultDeliveryUnsupported {
			app.logger.Info("container log result delivery unavailable")
			if !app.waitFor(ctx, unsupportedLogAPIDelay) {
				return
			}
		}
	}
}

func (app *App) runContainerControlWorker(ctx context.Context) {
	for {
		if ctx.Err() != nil {
			return
		}
		work, err := app.controlTransport.NextContainerControlWork(ctx)
		if err != nil {
			app.logger.Info("container control work poll unavailable")
			if !app.waitFor(ctx, containerControlBackoff(err)) {
				return
			}
			continue
		}
		if work == nil {
			if !app.waitFor(ctx, emptyControlPollDelay) {
				return
			}
			continue
		}
		result := app.executeContainerControlWork(ctx, *work)
		delivery := app.deliverContainerControlResult(
			ctx,
			&result,
			work.ExpiresAt)
		if delivery == resultDeliveryCancelled {
			return
		}
		if delivery == resultDeliveryUnsupported {
			app.logger.Info("container control result delivery unavailable")
			if !app.waitFor(ctx, unsupportedControlAPIDelay) {
				return
			}
		}
	}
}

func (app *App) executeContainerControlWork(
	ctx context.Context,
	work containercontrol.Work,
) containercontrol.Result {
	now := app.currentTime()
	result := containercontrol.Result{
		RequestID: work.RequestID,
		Status:    containercontrol.StatusFailed,
		Reason:    containercontrol.ReasonDockerUnavailable,
		Finished:  now.Truncate(time.Microsecond),
	}
	if err := work.Validate(now); err != nil {
		if !work.ExpiresAt.IsZero() && !now.Before(work.ExpiresAt) {
			result.Status = containercontrol.StatusExpired
			result.Reason = containercontrol.ReasonWorkExpired
		}
		return result
	}
	outcome := app.controlExecutor.ControlContainer(
		ctx,
		work.ContainerID,
		work.ComposeProject,
		work.Operation,
		app.config.MaxContainers,
		work.ExpiresAt)
	result.Status = outcome.Status
	result.Reason = outcome.ReasonCode
	result.Finished = app.currentTime().Truncate(time.Microsecond)
	return result
}

func (app *App) executeContainerLogWork(
	ctx context.Context,
	work containerlog.Work,
) containerlog.Result {
	result := containerlog.Result{
		RequestID:   work.RequestID,
		Status:      containerlog.StatusInvalidRequest,
		CollectedAt: app.currentTime().Truncate(time.Microsecond),
		Lines:       []containerlog.Line{},
	}
	if err := work.Validate(app.currentTime()); err != nil {
		return result
	}
	output, err := app.logReader.ContainerLogs(
		ctx,
		work.ContainerID,
		work.Tail,
		app.config.MaxContainers,
		work.ExpiresAt)
	result.CollectedAt = app.currentTime().Truncate(time.Microsecond)
	if err != nil {
		var readError containerlog.ReadError
		if errors.As(err, &readError) {
			switch readError.Kind {
			case containerlog.ReadNotFound:
				result.Status = containerlog.StatusNotFound
			case containerlog.ReadAmbiguous:
				result.Status = containerlog.StatusAmbiguous
			case containerlog.ReadNotAllowed:
				result.Status = containerlog.StatusNotAllowed
			default:
				result.Status = containerlog.StatusUnavailable
			}
		} else {
			result.Status = containerlog.StatusUnavailable
		}
		return result
	}
	result.Status = containerlog.StatusSuccess
	result.Truncated = output.Truncated
	result.RedactionApplied = output.RedactionApplied
	messageBytes := 0
	for _, line := range output.Lines {
		message, redactionApplied := containerlog.NormalizeAndRedact(
			[]byte(line.Message))
		result.RedactionApplied = result.RedactionApplied || redactionApplied
		if messageBytes+len(message) > containerlog.MaximumMessageBytes {
			result.Truncated = true
			continue
		}
		messageBytes += len(message)
		line.Message = message
		result.Lines = append(result.Lines, line)
	}
	return result
}

type resultDelivery int

const (
	resultDeliveryCompleted resultDelivery = iota
	resultDeliveryUnsupported
	resultDeliveryCancelled
)

func (app *App) deliverContainerLogResult(
	ctx context.Context,
	result *containerlog.Result,
	expiresAt time.Time,
) resultDelivery {
	defer func() {
		*result = containerlog.Result{}
	}()
	if err := containerlog.ValidateExpiry(expiresAt, app.currentTime()); err != nil {
		return resultDeliveryCompleted
	}
	delay := initialResultRetryDelay
	for {
		if ctx.Err() != nil {
			return resultDeliveryCancelled
		}
		remaining := expiresAt.Sub(app.currentTime())
		if remaining <= 0 {
			return resultDeliveryCompleted
		}
		attemptContext, cancel := context.WithTimeout(ctx, remaining)
		err := app.logTransport.SendContainerLogResult(
			attemptContext,
			*result)
		cancel()
		if err == nil {
			return resultDeliveryCompleted
		}
		var statusError transport.StatusError
		if errors.As(err, &statusError) {
			switch statusError.StatusCode {
			case 404, 405:
				return resultDeliveryUnsupported
			case 410:
				return resultDeliveryCompleted
			}
			retryableStatus := statusError.StatusCode == 408 ||
				statusError.StatusCode == 429 ||
				(statusError.StatusCode >= 500 && statusError.StatusCode < 600)
			if !retryableStatus {
				return resultDeliveryCompleted
			}
		}
		remaining = expiresAt.Sub(app.currentTime())
		if remaining <= 0 {
			return resultDeliveryCompleted
		}
		if delay > remaining {
			delay = remaining
		}
		if !app.waitFor(ctx, delay) {
			return resultDeliveryCancelled
		}
		delay = min(delay*2, maximumResultRetryDelay)
	}
}

func (app *App) deliverContainerControlResult(
	ctx context.Context,
	result *containercontrol.Result,
	expiresAt time.Time,
) resultDelivery {
	defer func() {
		*result = containercontrol.Result{}
	}()
	resultDeadline, err := containercontrol.ResultDeliveryDeadline(
		expiresAt,
		app.currentTime())
	if err != nil {
		return resultDeliveryCompleted
	}
	delay := initialControlResultRetryDelay
	for {
		if ctx.Err() != nil {
			return resultDeliveryCancelled
		}
		remaining := resultDeadline.Sub(app.currentTime())
		if remaining <= 0 {
			return resultDeliveryCompleted
		}
		attemptContext, cancel := context.WithTimeout(ctx, remaining)
		err := app.controlTransport.SendContainerControlResult(
			attemptContext,
			*result)
		cancel()
		if err == nil {
			return resultDeliveryCompleted
		}
		var statusError transport.StatusError
		if errors.As(err, &statusError) {
			switch statusError.StatusCode {
			case 404, 405:
				return resultDeliveryUnsupported
			case 410:
				return resultDeliveryCompleted
			}
			retryableStatus := statusError.StatusCode == 408 ||
				statusError.StatusCode == 429 ||
				(statusError.StatusCode >= 500 && statusError.StatusCode < 600)
			if !retryableStatus {
				return resultDeliveryCompleted
			}
		}
		remaining = resultDeadline.Sub(app.currentTime())
		if remaining <= 0 {
			return resultDeliveryCompleted
		}
		if delay > remaining {
			delay = remaining
		}
		if !app.waitFor(ctx, delay) {
			return resultDeliveryCancelled
		}
		delay = min(delay*2, maximumControlResultRetryDelay)
	}
}

func containerLogBackoff(err error) time.Duration {
	var statusError transport.StatusError
	if errors.As(err, &statusError) &&
		(statusError.StatusCode == 404 || statusError.StatusCode == 405) {
		return unsupportedLogAPIDelay
	}
	return transientLogErrorDelay
}

func containerControlBackoff(err error) time.Duration {
	var statusError transport.StatusError
	if errors.As(err, &statusError) &&
		(statusError.StatusCode == 404 || statusError.StatusCode == 405) {
		return unsupportedControlAPIDelay
	}
	return transientControlErrorDelay
}

func waitFor(ctx context.Context, delay time.Duration) bool {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-timer.C:
		return true
	}
}

func (app *App) currentTime() time.Time {
	if app.now != nil {
		return app.now().UTC()
	}
	return time.Now().UTC()
}

func (app *App) waitFor(ctx context.Context, delay time.Duration) bool {
	if app.wait != nil {
		return app.wait(ctx, delay)
	}
	return waitFor(ctx, delay)
}

func writeVersionProof(path string, version string, sentAt time.Time) error {
	if path == "" {
		return nil
	}
	if !fullSHA.MatchString(version) {
		return fmt.Errorf("write Agent version proof: version is not a full SHA")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0700); err != nil {
		return fmt.Errorf("create Agent version proof directory: %w", err)
	}
	temporary, err := os.CreateTemp(filepath.Dir(path), ".agent-version-proof.")
	if err != nil {
		return fmt.Errorf("create Agent version proof: %w", err)
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(0600); err != nil {
		temporary.Close()
		return fmt.Errorf("protect Agent version proof: %w", err)
	}
	contents := fmt.Sprintf(
		"VERSION=%s\nSENT_AT_UNIX=%d\n",
		version,
		sentAt.UTC().Unix())
	if _, err := temporary.WriteString(contents); err != nil {
		temporary.Close()
		return fmt.Errorf("write Agent version proof: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return fmt.Errorf("sync Agent version proof: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close Agent version proof: %w", err)
	}
	if err := os.Rename(temporaryPath, path); err != nil {
		return fmt.Errorf("promote Agent version proof: %w", err)
	}
	return nil
}

type errorsSentinel struct{}

func (errorsSentinel) Error() string {
	return "snapshot queued after delivery failure"
}

func newUUID() (string, error) {
	var bytes [16]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", fmt.Errorf("generate snapshot identifier: %w", err)
	}
	bytes[6] = (bytes[6] & 0x0f) | 0x40
	bytes[8] = (bytes[8] & 0x3f) | 0x80
	encoded := hex.EncodeToString(bytes[:])
	return encoded[0:8] + "-" +
		encoded[8:12] + "-" +
		encoded[12:16] + "-" +
		encoded[16:20] + "-" +
		encoded[20:32], nil
}
