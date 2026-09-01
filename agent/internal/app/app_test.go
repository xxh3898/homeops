package app

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/xxh3898/homeops/agent/internal/config"
	"github.com/xxh3898/homeops/agent/internal/containercontrol"
	"github.com/xxh3898/homeops/agent/internal/containerlog"
	"github.com/xxh3898/homeops/agent/internal/recovery"
	"github.com/xxh3898/homeops/agent/internal/snapshot"
	spoolpkg "github.com/xxh3898/homeops/agent/internal/spool"
	"github.com/xxh3898/homeops/agent/internal/transport"
)

func TestNewUUIDReturnsRFC4122Shape(t *testing.T) {
	t.Parallel()
	identifier, err := newUUID()
	if err != nil {
		t.Fatalf("newUUID returned an error: %v", err)
	}
	if len(identifier) != 36 || identifier[14] != '4' {
		t.Fatalf("identifier = %q, want version 4 UUID shape", identifier)
	}
}

func TestWriteVersionProofAtomicallyWritesExpectedPayload(t *testing.T) {
	t.Parallel()
	path := filepath.Join(t.TempDir(), "nested", "version-proof")
	version := "1111111111111111111111111111111111111111"
	sentAt := time.Unix(1_700_000_000, 0).UTC()
	if err := writeVersionProof(path, version, sentAt); err != nil {
		t.Fatalf("writeVersionProof returned an error: %v", err)
	}
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read version proof: %v", err)
	}
	if string(contents) != "VERSION="+version+"\nSENT_AT_UNIX=1700000000\n" {
		t.Fatalf("version proof = %q", contents)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("stat version proof: %v", err)
	}
	if info.Mode().Perm() != 0600 {
		t.Fatalf("mode = %o, want 600", info.Mode().Perm())
	}
}

func TestWriteVersionProofRejectsNonSHA(t *testing.T) {
	t.Parallel()
	if err := writeVersionProof(
		filepath.Join(t.TempDir(), "proof"),
		"dev",
		time.Now(),
	); err == nil {
		t.Fatal("writeVersionProof accepted a non-SHA version")
	}
}

func TestCollectAndSendStopsBeforeCollectionWhenPendingDrainFails(t *testing.T) {
	t.Parallel()
	drainFailure := errors.New("temporary delivery failure")
	host := &recordingHostCollector{}
	docker := &recordingDockerCollector{}
	transport := &recordingTransport{sendError: drainFailure}
	spoolStore := &recordingSpool{
		pendingPayload: []byte(`{"snapshotId":"older"}`),
	}
	application := &App{
		host:      host,
		docker:    docker,
		transport: transport,
		spool:     spoolStore,
		logger:    discardLogger(),
	}

	err := application.collectAndSend(context.Background())

	if !errors.Is(err, drainFailure) {
		t.Fatalf("collectAndSend error = %v, want drain failure", err)
	}
	if transport.sendCalls != 1 {
		t.Fatalf("transport send calls = %d, want 1", transport.sendCalls)
	}
	if host.collectCalls != 0 {
		t.Fatalf("host collect calls = %d, want 0", host.collectCalls)
	}
	if docker.containerCalls != 0 {
		t.Fatalf("Docker collection calls = %d, want 0", docker.containerCalls)
	}
	if spoolStore.storeCalls != 0 {
		t.Fatalf("spool store calls = %d, want 0", spoolStore.storeCalls)
	}
}

func TestCollectAndSendContinuesAfterPermanentRejectionsAreQuarantined(
	t *testing.T,
) {
	t.Parallel()
	host := &recordingHostCollector{}
	docker := &recordingDockerCollector{}
	transport := &recordingTransport{}
	spoolStore := &recordingSpool{
		drainResult: spoolpkg.DrainResult{Rejected: 2},
	}
	application := &App{
		config:    config.Config{MaxContainers: 128},
		host:      host,
		docker:    docker,
		transport: transport,
		spool:     spoolStore,
		logger:    discardLogger(),
	}

	err := application.collectAndSend(context.Background())

	if err != nil {
		t.Fatalf("collectAndSend returned an error: %v", err)
	}
	if host.collectCalls != 1 {
		t.Fatalf("host collect calls = %d, want 1", host.collectCalls)
	}
	if docker.containerCalls != 1 {
		t.Fatalf("Docker collection calls = %d, want 1", docker.containerCalls)
	}
	if transport.sendCalls != 1 {
		t.Fatalf("transport send calls = %d, want 1", transport.sendCalls)
	}
	if spoolStore.storeCalls != 0 {
		t.Fatalf("spool store calls = %d, want 0", spoolStore.storeCalls)
	}
}

func TestCollectAndSendAdvertisesConfiguredAgentCapabilities(t *testing.T) {
	t.Parallel()
	snapshotTransport := &recordingTransport{}
	docker := &recordingDockerCollector{containers: []snapshot.Container{{
		ID:                   "0123456789abcdef",
		NotificationsAllowed: true,
	}}}
	application := &App{
		config: config.Config{
			AgentID:       "local-mac",
			MaxContainers: 128,
		},
		version:           "1111111111111111111111111111111111111111",
		host:              &recordingHostCollector{},
		docker:            docker,
		transport:         snapshotTransport,
		spool:             &recordingSpool{},
		logger:            discardLogger(),
		recoveryExecutor:  &recordingRecoveryExecutor{},
		recoveryTransport: &recordingRecoveryTransport{},
	}

	if err := application.collectAndSend(context.Background()); err != nil {
		t.Fatalf("collectAndSend returned an error: %v", err)
	}
	var captured snapshot.Snapshot
	if err := json.Unmarshal(snapshotTransport.lastPayload, &captured); err != nil {
		t.Fatalf("decode snapshot payload: %v", err)
	}
	if !captured.SupportsContainerLogs {
		t.Fatal("supportsContainerLogs = false, want true")
	}
	if !captured.SupportsRhaomiRecovery {
		t.Fatal("supportsRhaomiRecovery = false, want true")
	}
	if len(captured.Containers) != 1 || !captured.Containers[0].NotificationsAllowed {
		t.Fatalf("containers = %#v, want bounded notification capability", captured.Containers)
	}
}

func TestExecuteContainerLogWorkRedactsBeforeResultTransport(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	application := &App{
		config: config.Config{MaxContainers: 128},
		logReader: &recordingLogReader{output: containerlog.Output{
			Lines: []containerlog.Line{{
				Stream:  containerlog.StreamStdout,
				Message: "token=synthetic-token",
			}},
		}},
		now: func() time.Time { return now },
	}
	work := containerlog.Work{
		RequestID:   "10000000-0000-4000-8000-000000000001",
		ContainerID: "0123456789ab",
		Tail:        50,
		ExpiresAt:   now.Add(6 * time.Second),
	}

	result := application.executeContainerLogWork(context.Background(), work)

	if result.Status != containerlog.StatusSuccess || len(result.Lines) != 1 ||
		result.Lines[0].Message != "token=[REDACTED]" ||
		!result.RedactionApplied || !result.CollectedAt.Equal(now) {
		t.Fatalf("result = %#v", result)
	}
}

func TestExecuteContainerControlWorkInvokesFixedExecutorOnce(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)
	executor := &recordingControlExecutor{outcome: containercontrol.Outcome{
		Status:     containercontrol.StatusApplied,
		ReasonCode: containercontrol.ReasonApplied,
	}}
	application := &App{
		config:          config.Config{MaxContainers: 128},
		controlExecutor: executor,
		now:             func() time.Time { return now },
	}
	work := validControlWork(now.Add(15 * time.Second))

	result := application.executeContainerControlWork(context.Background(), work)

	if executor.calls != 1 || result.Status != containercontrol.StatusApplied ||
		result.Reason != containercontrol.ReasonApplied ||
		result.RequestID != work.RequestID || !result.Finished.Equal(now) {
		t.Fatalf("executor/result = %d/%#v", executor.calls, result)
	}
}

func TestExecuteContainerControlWorkRejectsExpiryBeforeDockerExecutor(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)
	executor := &recordingControlExecutor{}
	application := &App{
		config:          config.Config{MaxContainers: 128},
		controlExecutor: executor,
		now:             func() time.Time { return now },
	}
	work := validControlWork(now)

	result := application.executeContainerControlWork(context.Background(), work)

	if result.Status != containercontrol.StatusExpired ||
		result.Reason != containercontrol.ReasonWorkExpired || executor.calls != 0 {
		t.Fatalf("result/calls = %#v/%d", result, executor.calls)
	}
}

func TestExecuteContainerLogWorkRejectsExpiredWorkBeforeReader(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	reader := &recordingLogReader{}
	application := &App{
		config:    config.Config{MaxContainers: 128},
		logReader: reader,
		now:       func() time.Time { return now },
	}

	result := application.executeContainerLogWork(
		context.Background(),
		containerlog.Work{
			RequestID:   "10000000-0000-4000-8000-000000000001",
			ContainerID: "0123456789ab",
			Tail:        50,
			ExpiresAt:   now,
		})

	if result.Status != containerlog.StatusInvalidRequest {
		t.Fatalf("status = %s, want %s", result.Status, containerlog.StatusInvalidRequest)
	}
	if reader.calls != 0 {
		t.Fatalf("reader calls = %d, want 0", reader.calls)
	}
}

func TestDeliverContainerLogResultRetriesTransientFailureThenSucceeds(
	t *testing.T,
) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)}
	logTransport := &recordingLogTransport{
		sendErrors: []error{errors.New("network unavailable"), nil},
	}
	spoolStore := &recordingSpool{}
	application := retryTestApp(clock, logTransport, spoolStore)
	result := successfulLogResult(clock.value)

	delivery := application.deliverContainerLogResult(
		context.Background(),
		&result,
		clock.value.Add(6*time.Second))

	if delivery != resultDeliveryCompleted || logTransport.sendCalls != 2 {
		t.Fatalf("delivery/calls = %d/%d", delivery, logTransport.sendCalls)
	}
	assertResultReleased(t, result)
	if spoolStore.storeCalls != 0 {
		t.Fatalf("spool store calls = %d, want 0", spoolStore.storeCalls)
	}
}

func TestDeliverContainerLogResultRetriesServerFailureThenSucceeds(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)}
	logTransport := &recordingLogTransport{
		sendErrors: []error{
			transport.StatusError{StatusCode: 503},
			nil,
		},
	}
	application := retryTestApp(clock, logTransport, &recordingSpool{})
	result := successfulLogResult(clock.value)

	delivery := application.deliverContainerLogResult(
		context.Background(),
		&result,
		clock.value.Add(6*time.Second))

	if delivery != resultDeliveryCompleted || logTransport.sendCalls != 2 {
		t.Fatalf("delivery/calls = %d/%d", delivery, logTransport.sendCalls)
	}
	assertResultReleased(t, result)
}

func TestDeliverContainerLogResultTreatsOldAPIAsUnsupported(t *testing.T) {
	t.Parallel()
	for _, status := range []int{404, 405} {
		status := status
		t.Run(fmt.Sprintf("status-%d", status), func(t *testing.T) {
			t.Parallel()
			clock := &mutableTime{value: time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)}
			logTransport := &recordingLogTransport{
				sendError: transport.StatusError{StatusCode: status},
			}
			application := retryTestApp(clock, logTransport, &recordingSpool{})
			result := successfulLogResult(clock.value)

			delivery := application.deliverContainerLogResult(
				context.Background(),
				&result,
				clock.value.Add(6*time.Second))

			if delivery != resultDeliveryUnsupported || logTransport.sendCalls != 1 {
				t.Fatalf("delivery/calls = %d/%d", delivery, logTransport.sendCalls)
			}
			assertResultReleased(t, result)
		})
	}
}

func TestDeliverContainerLogResultStopsAtExpiryAndReleasesPayload(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)}
	logTransport := &recordingLogTransport{sendError: errors.New("network unavailable")}
	spoolStore := &recordingSpool{}
	application := retryTestApp(clock, logTransport, spoolStore)
	result := successfulLogResult(clock.value)

	delivery := application.deliverContainerLogResult(
		context.Background(),
		&result,
		clock.value.Add(250*time.Millisecond))

	if delivery != resultDeliveryCompleted || logTransport.sendCalls != 2 {
		t.Fatalf("delivery/calls = %d/%d", delivery, logTransport.sendCalls)
	}
	assertResultReleased(t, result)
	if spoolStore.storeCalls != 0 {
		t.Fatalf("spool store calls = %d, want 0", spoolStore.storeCalls)
	}
}

func TestDeliverContainerLogResultDoesNotRetryGone(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)}
	logTransport := &recordingLogTransport{
		sendError: transport.StatusError{StatusCode: 410},
	}
	application := retryTestApp(clock, logTransport, &recordingSpool{})
	result := successfulLogResult(clock.value)

	delivery := application.deliverContainerLogResult(
		context.Background(),
		&result,
		clock.value.Add(6*time.Second))

	if delivery != resultDeliveryCompleted || logTransport.sendCalls != 1 {
		t.Fatalf("delivery/calls = %d/%d", delivery, logTransport.sendCalls)
	}
	assertResultReleased(t, result)
}

func TestDeliverContainerLogResultRejectsUnboundedExpiryWithoutSending(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)}
	logTransport := &recordingLogTransport{}
	application := retryTestApp(clock, logTransport, &recordingSpool{})
	result := successfulLogResult(clock.value)

	delivery := application.deliverContainerLogResult(
		context.Background(),
		&result,
		clock.value.Add(containerlog.MaximumExpiryHorizon+time.Nanosecond))

	if delivery != resultDeliveryCompleted || logTransport.sendCalls != 0 {
		t.Fatalf("delivery/calls = %d/%d", delivery, logTransport.sendCalls)
	}
	assertResultReleased(t, result)
}

func TestDeliverContainerLogResultStopsImmediatelyOnCancellation(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)}
	logTransport := &recordingLogTransport{sendError: errors.New("network unavailable")}
	ctx, cancel := context.WithCancel(context.Background())
	application := &App{
		logTransport: logTransport,
		now:          func() time.Time { return clock.value },
		wait: func(context.Context, time.Duration) bool {
			cancel()
			return false
		},
	}
	result := successfulLogResult(clock.value)

	delivery := application.deliverContainerLogResult(
		ctx,
		&result,
		clock.value.Add(6*time.Second))

	if delivery != resultDeliveryCancelled || logTransport.sendCalls != 1 {
		t.Fatalf("delivery/calls = %d/%d", delivery, logTransport.sendCalls)
	}
	assertResultReleased(t, result)
}

func TestDeliverContainerControlResultRetriesTransportOnlyAndReleasesPayload(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)}
	controlTransport := &recordingControlTransport{
		sendErrors: []error{errors.New("network unavailable"), nil},
	}
	application := controlRetryTestApp(clock, controlTransport)
	result := successfulControlResult(clock.value)

	delivery := application.deliverContainerControlResult(
		context.Background(),
		&result,
		clock.value.Add(15*time.Second))

	if delivery != resultDeliveryCompleted || controlTransport.sendCalls != 2 {
		t.Fatalf("delivery/calls = %d/%d", delivery, controlTransport.sendCalls)
	}
	assertControlResultReleased(t, result)
}

func TestDeliverContainerControlResultAcceptsResultAfterOperationExpiry(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 1, 0, time.UTC)}
	controlTransport := &recordingControlTransport{}
	application := controlRetryTestApp(clock, controlTransport)
	result := successfulControlResult(clock.value.Add(-time.Second))

	delivery := application.deliverContainerControlResult(
		context.Background(),
		&result,
		clock.value.Add(-time.Second))

	if delivery != resultDeliveryCompleted || controlTransport.sendCalls != 1 {
		t.Fatalf("delivery/calls = %d/%d", delivery, controlTransport.sendCalls)
	}
	assertControlResultReleased(t, result)
}

func TestDeliverContainerControlResultRetriesKnownTransientStatuses(t *testing.T) {
	t.Parallel()
	for _, status := range []int{408, 429, 500, 503} {
		status := status
		t.Run(fmt.Sprintf("status-%d", status), func(t *testing.T) {
			t.Parallel()
			clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)}
			controlTransport := &recordingControlTransport{
				sendErrors: []error{transport.StatusError{StatusCode: status}, nil},
			}
			application := controlRetryTestApp(clock, controlTransport)
			result := successfulControlResult(clock.value)

			delivery := application.deliverContainerControlResult(
				context.Background(),
				&result,
				clock.value.Add(15*time.Second))

			if delivery != resultDeliveryCompleted || controlTransport.sendCalls != 2 {
				t.Fatalf("delivery/calls = %d/%d", delivery, controlTransport.sendCalls)
			}
			assertControlResultReleased(t, result)
		})
	}
}

func TestDeliverContainerControlResultDoesNotRetryGoneOrTerminalFailure(t *testing.T) {
	t.Parallel()
	for _, status := range []int{400, 410, 422} {
		status := status
		t.Run(fmt.Sprintf("status-%d", status), func(t *testing.T) {
			t.Parallel()
			clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)}
			controlTransport := &recordingControlTransport{
				sendError: transport.StatusError{StatusCode: status},
			}
			application := controlRetryTestApp(clock, controlTransport)
			result := successfulControlResult(clock.value)

			delivery := application.deliverContainerControlResult(
				context.Background(),
				&result,
				clock.value.Add(15*time.Second))

			if delivery != resultDeliveryCompleted || controlTransport.sendCalls != 1 {
				t.Fatalf("delivery/calls = %d/%d", delivery, controlTransport.sendCalls)
			}
			assertControlResultReleased(t, result)
		})
	}
}

func TestDeliverContainerControlResultTreatsOldAPIAsUnsupported(t *testing.T) {
	t.Parallel()
	for _, status := range []int{404, 405} {
		status := status
		t.Run(fmt.Sprintf("status-%d", status), func(t *testing.T) {
			t.Parallel()
			clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)}
			controlTransport := &recordingControlTransport{
				sendError: transport.StatusError{StatusCode: status},
			}
			application := controlRetryTestApp(clock, controlTransport)
			result := successfulControlResult(clock.value)

			delivery := application.deliverContainerControlResult(
				context.Background(),
				&result,
				clock.value.Add(15*time.Second))

			if delivery != resultDeliveryUnsupported || controlTransport.sendCalls != 1 {
				t.Fatalf("delivery/calls = %d/%d", delivery, controlTransport.sendCalls)
			}
			assertControlResultReleased(t, result)
		})
	}
}

func TestDeliverContainerControlResultStopsAtReportingDeadlineWithoutPersistence(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)}
	controlTransport := &recordingControlTransport{sendError: errors.New("network unavailable")}
	application := controlRetryTestApp(clock, controlTransport)
	result := successfulControlResult(clock.value)
	expiresAt := clock.value.Add(
		-containercontrol.ResultReportingGrace + 250*time.Millisecond)

	delivery := application.deliverContainerControlResult(
		context.Background(),
		&result,
		expiresAt)

	if delivery != resultDeliveryCompleted || controlTransport.sendCalls != 2 {
		t.Fatalf("delivery/calls = %d/%d", delivery, controlTransport.sendCalls)
	}
	assertControlResultReleased(t, result)
}

func TestDeliverContainerControlResultDropsPayloadAfterReportingGrace(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)}
	controlTransport := &recordingControlTransport{}
	application := controlRetryTestApp(clock, controlTransport)
	result := successfulControlResult(clock.value.Add(-containercontrol.ResultReportingGrace))

	delivery := application.deliverContainerControlResult(
		context.Background(),
		&result,
		clock.value.Add(-containercontrol.ResultReportingGrace))

	if delivery != resultDeliveryCompleted || controlTransport.sendCalls != 0 {
		t.Fatalf("delivery/calls = %d/%d", delivery, controlTransport.sendCalls)
	}
	assertControlResultReleased(t, result)
}

func TestRunKeepsSnapshotLoopAliveWhenOldAPIReturns404(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Millisecond)
	defer cancel()
	snapshotTransport := &recordingTransport{}
	logTransport := &recordingLogTransport{
		nextError: transport.StatusError{StatusCode: 404},
	}
	application := &App{
		config: config.Config{
			AgentID:       "local-mac",
			Interval:      5 * time.Millisecond,
			MaxContainers: 128,
		},
		version:      "1111111111111111111111111111111111111111",
		host:         &recordingHostCollector{},
		docker:       &recordingDockerCollector{},
		transport:    snapshotTransport,
		logReader:    &recordingLogReader{},
		logTransport: logTransport,
		spool:        &recordingSpool{},
		logger:       discardLogger(),
	}

	if err := application.Run(ctx); err != nil {
		t.Fatalf("Run returned an error: %v", err)
	}
	if snapshotTransport.sendCalls < 2 {
		t.Fatalf("snapshot send calls = %d, want at least 2", snapshotTransport.sendCalls)
	}
	if logTransport.nextCalls != 1 {
		t.Fatalf("log poll calls = %d, want 1 bounded old-API attempt", logTransport.nextCalls)
	}
}

func TestRunKeepsSnapshotLoopAliveWhenOldResultAPIReturns404(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Millisecond)
	defer cancel()
	now := time.Now().UTC()
	snapshotTransport := &recordingTransport{}
	logTransport := &recordingLogTransport{
		nextWork: &containerlog.Work{
			RequestID:   "10000000-0000-4000-8000-000000000001",
			ContainerID: "0123456789ab",
			Tail:        50,
			ExpiresAt:   now.Add(6 * time.Second),
		},
		sendError: transport.StatusError{StatusCode: 404},
	}
	application := &App{
		config: config.Config{
			AgentID:       "local-mac",
			Interval:      5 * time.Millisecond,
			MaxContainers: 128,
		},
		version:      "1111111111111111111111111111111111111111",
		host:         &recordingHostCollector{},
		docker:       &recordingDockerCollector{},
		transport:    snapshotTransport,
		logReader:    &recordingLogReader{},
		logTransport: logTransport,
		spool:        &recordingSpool{},
		logger:       discardLogger(),
	}

	if err := application.Run(ctx); err != nil {
		t.Fatalf("Run returned an error: %v", err)
	}
	if snapshotTransport.sendCalls < 2 {
		t.Fatalf("snapshot send calls = %d, want at least 2", snapshotTransport.sendCalls)
	}
	if logTransport.sendCalls != 1 {
		t.Fatalf("result send calls = %d, want 1", logTransport.sendCalls)
	}
}

func TestRunKeepsSnapshotAndLogLoopsAliveWhenOldControlAPIReturns404(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithTimeout(context.Background(), 35*time.Millisecond)
	defer cancel()
	snapshotTransport := &recordingTransport{}
	logTransport := &recordingLogTransport{}
	controlTransport := &recordingControlTransport{
		nextError: transport.StatusError{StatusCode: 404},
	}
	application := &App{
		config: config.Config{
			AgentID:       "local-mac",
			Interval:      5 * time.Millisecond,
			MaxContainers: 128,
		},
		version:          "1111111111111111111111111111111111111111",
		host:             &recordingHostCollector{},
		docker:           &recordingDockerCollector{},
		transport:        snapshotTransport,
		logReader:        &recordingLogReader{},
		logTransport:     logTransport,
		controlExecutor:  &recordingControlExecutor{},
		controlTransport: controlTransport,
		spool:            &recordingSpool{},
		logger:           discardLogger(),
	}

	if err := application.Run(ctx); err != nil {
		t.Fatalf("Run returned an error: %v", err)
	}
	if snapshotTransport.sendCalls < 2 || logTransport.nextCalls < 1 {
		t.Fatalf("snapshot/log calls = %d/%d", snapshotTransport.sendCalls, logTransport.nextCalls)
	}
	if controlTransport.nextCalls != 1 {
		t.Fatalf("control poll calls = %d, want 1 bounded old-API attempt", controlTransport.nextCalls)
	}
}

func TestControlResultDeliveryRetryNeverReexecutesDockerOperation(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)
	executor := &recordingControlExecutor{outcome: containercontrol.Outcome{
		Status:     containercontrol.StatusApplied,
		ReasonCode: containercontrol.ReasonApplied,
	}}
	controlTransport := &recordingControlTransport{
		sendErrors: []error{errors.New("network unavailable"), nil},
	}
	clock := &mutableTime{value: now}
	application := controlRetryTestApp(clock, controlTransport)
	application.config.MaxContainers = 128
	application.controlExecutor = executor
	work := validControlWork(now.Add(15 * time.Second))

	result := application.executeContainerControlWork(context.Background(), work)
	delivery := application.deliverContainerControlResult(
		context.Background(), &result, work.ExpiresAt)

	if delivery != resultDeliveryCompleted || executor.calls != 1 || controlTransport.sendCalls != 2 {
		t.Fatalf("delivery/executor/send = %d/%d/%d",
			delivery, executor.calls, controlTransport.sendCalls)
	}
}

func TestRecoveryResultDeliveryRetryNeverReexecutesCapability(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 9, 1, 12, 0, 0, 0, time.UTC)
	clock := &mutableTime{value: now}
	executor := &recordingRecoveryExecutor{result: successfulRecoveryResult(now)}
	recoveryTransport := &recordingRecoveryTransport{
		sendErrors: []error{errors.New("network unavailable"), nil},
	}
	application := recoveryRetryTestApp(clock, recoveryTransport)
	application.recoveryExecutor = executor
	work := validRecoveryWork(now.Add(10 * time.Second))

	result := application.recoveryExecutor.Execute(
		context.Background(), work, application.currentTime())
	delivery := application.deliverAutomaticRecoveryResult(
		context.Background(), &result, work.ExpiresAt)

	if delivery != resultDeliveryCompleted || executor.calls != 1 ||
		recoveryTransport.sendCalls != 2 {
		t.Fatalf("delivery/executor/send = %d/%d/%d",
			delivery, executor.calls, recoveryTransport.sendCalls)
	}
	assertRecoveryResultReleased(t, result)
}

func TestDeliverAutomaticRecoveryResultDoesNotRetryGoneOrTerminalFailure(t *testing.T) {
	t.Parallel()
	for _, status := range []int{400, 410, 422} {
		status := status
		t.Run(fmt.Sprintf("status-%d", status), func(t *testing.T) {
			t.Parallel()
			clock := &mutableTime{value: time.Date(2026, 9, 1, 12, 0, 0, 0, time.UTC)}
			recoveryTransport := &recordingRecoveryTransport{
				sendError: transport.StatusError{StatusCode: status},
			}
			application := recoveryRetryTestApp(clock, recoveryTransport)
			result := successfulRecoveryResult(clock.value)

			delivery := application.deliverAutomaticRecoveryResult(
				context.Background(),
				&result,
				clock.value.Add(10*time.Second))

			if delivery != resultDeliveryCompleted || recoveryTransport.sendCalls != 1 {
				t.Fatalf("delivery/calls = %d/%d", delivery, recoveryTransport.sendCalls)
			}
			assertRecoveryResultReleased(t, result)
		})
	}
}

func TestAutomaticRecoveryBackoffClassifiesOldAPIAsUnsupported(t *testing.T) {
	t.Parallel()
	if delay := automaticRecoveryBackoff(
		transport.StatusError{StatusCode: 404}); delay != unsupportedRecoveryAPIDelay {
		t.Fatalf("404 backoff = %s, want %s", delay, unsupportedRecoveryAPIDelay)
	}
	if delay := automaticRecoveryBackoff(
		errors.New("network")); delay != transientRecoveryErrorDelay {
		t.Fatalf("network backoff = %s, want %s", delay, transientRecoveryErrorDelay)
	}
}

func TestPostSendUnknownResultIsDeliveredWithinGraceWithoutReexecution(t *testing.T) {
	t.Parallel()
	clock := &mutableTime{value: time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)}
	expiresAt := clock.value.Add(250 * time.Millisecond)
	executor := &recordingControlExecutor{
		outcome: containercontrol.Outcome{
			Status:     containercontrol.StatusOutcomeUnknown,
			ReasonCode: containercontrol.ReasonDockerOutcomeUnknown,
		},
		onCall: func() {
			clock.value = expiresAt.Add(50 * time.Millisecond)
		},
	}
	controlTransport := &recordingControlTransport{}
	application := controlRetryTestApp(clock, controlTransport)
	application.config.MaxContainers = 128
	application.controlExecutor = executor
	work := validControlWork(expiresAt)

	result := application.executeContainerControlWork(context.Background(), work)
	delivery := application.deliverContainerControlResult(
		context.Background(), &result, work.ExpiresAt)

	if delivery != resultDeliveryCompleted || executor.calls != 1 ||
		controlTransport.sendCalls != 1 ||
		controlTransport.lastResult.Status != containercontrol.StatusOutcomeUnknown ||
		controlTransport.lastResult.Reason != containercontrol.ReasonDockerOutcomeUnknown {
		t.Fatalf("delivery/executor/send/result = %d/%d/%d/%#v",
			delivery, executor.calls, controlTransport.sendCalls,
			controlTransport.lastResult)
	}
}

func TestContainerLogBackoffClassifiesOldAPIAsUnsupported(t *testing.T) {
	t.Parallel()
	if delay := containerLogBackoff(
		transport.StatusError{StatusCode: 405}); delay != unsupportedLogAPIDelay {
		t.Fatalf("405 backoff = %s, want %s", delay, unsupportedLogAPIDelay)
	}
	if delay := containerLogBackoff(
		errors.New("network")); delay != transientLogErrorDelay {
		t.Fatalf("network backoff = %s, want %s", delay, transientLogErrorDelay)
	}
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

type recordingHostCollector struct {
	collectCalls int
}

func (collector *recordingHostCollector) Collect(
	context.Context,
) (snapshot.Host, error) {
	collector.collectCalls++
	return snapshot.Host{}, nil
}

type recordingDockerCollector struct {
	containerCalls int
	containers     []snapshot.Container
}

func (collector *recordingDockerCollector) Containers(
	context.Context,
	int,
) ([]snapshot.Container, error) {
	collector.containerCalls++
	return collector.containers, nil
}

type recordingTransport struct {
	sendCalls   int
	sendError   error
	lastPayload []byte
}

func (transport *recordingTransport) Send(
	_ context.Context,
	payload []byte,
) error {
	transport.sendCalls++
	transport.lastPayload = append([]byte(nil), payload...)
	return transport.sendError
}

type recordingLogReader struct {
	output containerlog.Output
	err    error
	calls  int
}

func (reader *recordingLogReader) ContainerLogs(
	context.Context,
	string,
	int,
	int,
	time.Time,
) (containerlog.Output, error) {
	reader.calls++
	return reader.output, reader.err
}

type recordingLogTransport struct {
	nextCalls  int
	nextError  error
	nextWork   *containerlog.Work
	sendCalls  int
	sendError  error
	sendErrors []error
}

type recordingControlExecutor struct {
	outcome containercontrol.Outcome
	calls   int
	onCall  func()
}

func (executor *recordingControlExecutor) ControlContainer(
	context.Context,
	string,
	string,
	containercontrol.Operation,
	int,
	time.Time,
) containercontrol.Outcome {
	executor.calls++
	if executor.onCall != nil {
		executor.onCall()
	}
	return executor.outcome
}

type recordingControlTransport struct {
	nextCalls  int
	nextError  error
	nextWork   *containercontrol.Work
	sendCalls  int
	sendError  error
	sendErrors []error
	lastResult containercontrol.Result
}

type recordingRecoveryExecutor struct {
	result recovery.Result
	calls  int
}

func (executor *recordingRecoveryExecutor) Execute(
	_ context.Context,
	_ recovery.Work,
	_ time.Time,
) recovery.Result {
	executor.calls++
	return executor.result
}

type recordingRecoveryTransport struct {
	nextCalls  int
	nextError  error
	nextWork   *recovery.Work
	sendCalls  int
	sendError  error
	sendErrors []error
	lastResult recovery.Result
}

func (transport *recordingRecoveryTransport) NextAutomaticRecoveryWork(
	context.Context,
) (*recovery.Work, error) {
	transport.nextCalls++
	work := transport.nextWork
	transport.nextWork = nil
	return work, transport.nextError
}

func (transport *recordingRecoveryTransport) SendAutomaticRecoveryResult(
	_ context.Context,
	result recovery.Result,
) error {
	transport.sendCalls++
	transport.lastResult = result
	if len(transport.sendErrors) > 0 {
		err := transport.sendErrors[0]
		transport.sendErrors = transport.sendErrors[1:]
		return err
	}
	return transport.sendError
}

func (controlTransport *recordingControlTransport) NextContainerControlWork(
	context.Context,
) (*containercontrol.Work, error) {
	controlTransport.nextCalls++
	work := controlTransport.nextWork
	controlTransport.nextWork = nil
	return work, controlTransport.nextError
}

func (controlTransport *recordingControlTransport) SendContainerControlResult(
	_ context.Context,
	result containercontrol.Result,
) error {
	controlTransport.sendCalls++
	controlTransport.lastResult = result
	if len(controlTransport.sendErrors) > 0 {
		err := controlTransport.sendErrors[0]
		controlTransport.sendErrors = controlTransport.sendErrors[1:]
		return err
	}
	return controlTransport.sendError
}

func (logTransport *recordingLogTransport) NextContainerLogWork(
	context.Context,
) (*containerlog.Work, error) {
	logTransport.nextCalls++
	work := logTransport.nextWork
	logTransport.nextWork = nil
	return work, logTransport.nextError
}

func (logTransport *recordingLogTransport) SendContainerLogResult(
	context.Context,
	containerlog.Result,
) error {
	logTransport.sendCalls++
	if len(logTransport.sendErrors) > 0 {
		err := logTransport.sendErrors[0]
		logTransport.sendErrors = logTransport.sendErrors[1:]
		return err
	}
	return logTransport.sendError
}

type recordingSpool struct {
	pendingPayload []byte
	drainResult    spoolpkg.DrainResult
	storeCalls     int
}

func (spool *recordingSpool) Drain(
	send func([]byte) error,
) (spoolpkg.DrainResult, error) {
	if spool.pendingPayload != nil {
		if err := send(spool.pendingPayload); err != nil {
			return spool.drainResult, err
		}
	}
	return spool.drainResult, nil
}

func (spool *recordingSpool) Store(string, []byte) error {
	spool.storeCalls++
	return nil
}

type mutableTime struct {
	value time.Time
}

func retryTestApp(
	clock *mutableTime,
	logTransport *recordingLogTransport,
	spoolStore *recordingSpool,
) *App {
	return &App{
		logTransport: logTransport,
		spool:        spoolStore,
		now:          func() time.Time { return clock.value },
		wait: func(ctx context.Context, delay time.Duration) bool {
			if ctx.Err() != nil {
				return false
			}
			clock.value = clock.value.Add(delay)
			return true
		},
	}
}

func controlRetryTestApp(
	clock *mutableTime,
	controlTransport *recordingControlTransport,
) *App {
	return &App{
		controlTransport: controlTransport,
		now:              func() time.Time { return clock.value },
		wait: func(ctx context.Context, delay time.Duration) bool {
			if ctx.Err() != nil {
				return false
			}
			clock.value = clock.value.Add(delay)
			return true
		},
	}
}

func recoveryRetryTestApp(
	clock *mutableTime,
	recoveryTransport *recordingRecoveryTransport,
) *App {
	return &App{
		recoveryTransport: recoveryTransport,
		now:               func() time.Time { return clock.value },
		wait: func(ctx context.Context, delay time.Duration) bool {
			if ctx.Err() != nil {
				return false
			}
			clock.value = clock.value.Add(delay)
			return true
		},
	}
}

func validControlWork(expiresAt time.Time) containercontrol.Work {
	return containercontrol.Work{
		RequestID:      "10000000-0000-4000-8000-000000000001",
		ContainerID:    "0123456789ab",
		ComposeProject: "example",
		Operation:      containercontrol.OperationStart,
		ExpiresAt:      expiresAt,
	}
}

func successfulControlResult(finishedAt time.Time) containercontrol.Result {
	return containercontrol.Result{
		RequestID: "10000000-0000-4000-8000-000000000001",
		Status:    containercontrol.StatusApplied,
		Reason:    containercontrol.ReasonApplied,
		Finished:  finishedAt,
	}
}

func validRecoveryWork(expiresAt time.Time) recovery.Work {
	return recovery.Work{
		RequestID: "10000000-0000-4000-8000-000000000119",
		Project:   recovery.ProjectRhaomi,
		Target:    recovery.TargetBackend,
		Action:    recovery.ActionRestart,
		ExpiresAt: expiresAt,
	}
}

func successfulRecoveryResult(finishedAt time.Time) recovery.Result {
	return recovery.Result{
		RequestID:    "10000000-0000-4000-8000-000000000119",
		Status:       recovery.StatusApplied,
		ReasonCode:   recovery.ReasonRecoveryApplied,
		StartedAt:    finishedAt.Add(-time.Second),
		FinishedAt:   finishedAt,
		PreHealth:    recovery.HealthDown,
		PostHealth:   recovery.HealthUp,
		RestartCount: 1,
	}
}

func assertRecoveryResultReleased(t *testing.T, result recovery.Result) {
	t.Helper()
	if result.RequestID != "" || result.Status != "" || result.ReasonCode != "" ||
		!result.StartedAt.IsZero() || !result.FinishedAt.IsZero() ||
		result.PreHealth != "" || result.PostHealth != "" || result.RestartCount != 0 {
		t.Fatalf("recovery result payload reference was retained: %#v", result)
	}
}

func assertControlResultReleased(t *testing.T, result containercontrol.Result) {
	t.Helper()
	if result.RequestID != "" || result.Status != "" ||
		result.Reason != "" || !result.Finished.IsZero() {
		t.Fatalf("control result payload reference was retained: %#v", result)
	}
}

func successfulLogResult(collectedAt time.Time) containerlog.Result {
	return containerlog.Result{
		RequestID:        "10000000-0000-4000-8000-000000000001",
		Status:           containerlog.StatusSuccess,
		CollectedAt:      collectedAt,
		Lines:            []containerlog.Line{{Stream: containerlog.StreamStdout, Message: "safe"}},
		RedactionApplied: true,
	}
}

func assertResultReleased(t *testing.T, result containerlog.Result) {
	t.Helper()
	if result.RequestID != "" || result.Lines != nil || !result.CollectedAt.IsZero() {
		t.Fatalf("result payload reference was retained: %#v", result)
	}
}
