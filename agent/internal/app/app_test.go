package app

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"testing"

	"github.com/xxh3898/homeops/agent/internal/config"
	"github.com/xxh3898/homeops/agent/internal/snapshot"
	spoolpkg "github.com/xxh3898/homeops/agent/internal/spool"
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
	sendCalls int
	sendError error
}

func (transport *recordingTransport) Send(
	context.Context,
	[]byte,
) error {
	transport.sendCalls++
	return transport.sendError
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
