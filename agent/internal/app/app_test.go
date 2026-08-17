package app

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/xxh3898/homeops/agent/internal/config"
	"github.com/xxh3898/homeops/agent/internal/containerlog"
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

func TestCollectAndSendAdvertisesContainerLogCapability(t *testing.T) {
	t.Parallel()
	snapshotTransport := &recordingTransport{}
	application := &App{
		config: config.Config{
			AgentID:       "local-mac",
			MaxContainers: 128,
		},
		version:   "1111111111111111111111111111111111111111",
		host:      &recordingHostCollector{},
		docker:    &recordingDockerCollector{},
		transport: snapshotTransport,
		spool:     &recordingSpool{},
		logger:    discardLogger(),
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
}

func TestExecuteContainerLogWorkRedactsBeforeResultTransport(t *testing.T) {
	t.Parallel()
	application := &App{
		config: config.Config{MaxContainers: 128},
		logReader: &recordingLogReader{output: containerlog.Output{
			Lines: []containerlog.Line{{
				Stream:  containerlog.StreamStdout,
				Message: "token=synthetic-token",
			}},
		}},
	}
	work := containerlog.Work{
		RequestID:   "10000000-0000-4000-8000-000000000001",
		ContainerID: "0123456789ab",
		Tail:        50,
	}

	result := application.executeContainerLogWork(context.Background(), work)

	if result.Status != containerlog.StatusSuccess || len(result.Lines) != 1 ||
		result.Lines[0].Message != "token=[REDACTED]" {
		t.Fatalf("result = %#v", result)
	}
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
}

func (collector *recordingDockerCollector) Containers(
	context.Context,
	int,
) ([]snapshot.Container, error) {
	collector.containerCalls++
	return nil, nil
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
}

func (reader *recordingLogReader) ContainerLogs(
	context.Context,
	string,
	int,
	int,
) (containerlog.Output, error) {
	return reader.output, reader.err
}

type recordingLogTransport struct {
	nextCalls int
	nextError error
}

func (logTransport *recordingLogTransport) NextContainerLogWork(
	context.Context,
) (*containerlog.Work, error) {
	logTransport.nextCalls++
	return nil, logTransport.nextError
}

func (logTransport *recordingLogTransport) SendContainerLogResult(
	context.Context,
	containerlog.Result,
) error {
	return nil
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
