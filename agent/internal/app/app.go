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
	"github.com/xxh3898/homeops/agent/internal/containerlog"
	"github.com/xxh3898/homeops/agent/internal/docker"
	"github.com/xxh3898/homeops/agent/internal/snapshot"
	"github.com/xxh3898/homeops/agent/internal/spool"
	"github.com/xxh3898/homeops/agent/internal/transport"
)

type App struct {
	config       config.Config
	version      string
	host         hostCollector
	docker       dockerCollector
	transport    snapshotTransport
	logReader    containerLogReader
	logTransport containerLogTransport
	spool        snapshotSpool
	logger       *slog.Logger
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
	ContainerLogs(context.Context, string, int, int) (containerlog.Output, error)
}

type containerLogTransport interface {
	NextContainerLogWork(context.Context) (*containerlog.Work, error)
	SendContainerLogResult(context.Context, containerlog.Result) error
}

type snapshotSpool interface {
	Drain(func([]byte) error) (spool.DrainResult, error)
	Store(string, []byte) error
}

const collectionTimeout = 20 * time.Second

const (
	unsupportedLogAPIDelay = 30 * time.Second
	transientLogErrorDelay = time.Second
	emptyLogPollDelay      = 250 * time.Millisecond
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
		config:       config,
		version:      version,
		host:         collector.NewHostCollector(collector.ExecRunner{}),
		docker:       dockerClient,
		transport:    transportClient,
		logReader:    dockerClient,
		logTransport: transportClient,
		spool:        spoolStore,
		logger:       logger,
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
	now := time.Now().UTC().Truncate(time.Microsecond)
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
			if !waitFor(ctx, containerLogBackoff(err)) {
				return
			}
			continue
		}
		if work == nil {
			if !waitFor(ctx, emptyLogPollDelay) {
				return
			}
			continue
		}
		result := app.executeContainerLogWork(ctx, *work)
		if err := app.logTransport.SendContainerLogResult(ctx, result); err != nil {
			app.logger.Info("container log result delivery unavailable")
			if !waitFor(ctx, containerLogBackoff(err)) {
				return
			}
		}
	}
}

func (app *App) executeContainerLogWork(
	ctx context.Context,
	work containerlog.Work,
) containerlog.Result {
	result := containerlog.Result{
		RequestID: work.RequestID,
		Status:    containerlog.StatusInvalidRequest,
		Lines:     []containerlog.Line{},
	}
	if err := work.Validate(); err != nil {
		return result
	}
	output, err := app.logReader.ContainerLogs(
		ctx,
		work.ContainerID,
		work.Tail,
		app.config.MaxContainers)
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
	messageBytes := 0
	for _, line := range output.Lines {
		message := containerlog.NormalizeAndRedact([]byte(line.Message))
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

func containerLogBackoff(err error) time.Duration {
	var statusError transport.StatusError
	if errors.As(err, &statusError) &&
		(statusError.StatusCode == 404 || statusError.StatusCode == 405) {
		return unsupportedLogAPIDelay
	}
	return transientLogErrorDelay
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
