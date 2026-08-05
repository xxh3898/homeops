package app

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"time"

	"github.com/xxh3898/homeops/agent/internal/collector"
	"github.com/xxh3898/homeops/agent/internal/config"
	"github.com/xxh3898/homeops/agent/internal/docker"
	"github.com/xxh3898/homeops/agent/internal/snapshot"
	"github.com/xxh3898/homeops/agent/internal/spool"
	"github.com/xxh3898/homeops/agent/internal/transport"
)

type App struct {
	config    config.Config
	version   string
	host      hostCollector
	docker    dockerCollector
	transport snapshotTransport
	spool     snapshotSpool
	logger    *slog.Logger
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

type snapshotSpool interface {
	Drain(func([]byte) error) (spool.DrainResult, error)
	Store(string, []byte) error
}

const collectionTimeout = 20 * time.Second

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
		config:    config,
		version:   version,
		host:      collector.NewHostCollector(collector.ExecRunner{}),
		docker:    dockerClient,
		transport: transportClient,
		spool:     spoolStore,
		logger:    logger,
	}, nil
}

func (app *App) Run(ctx context.Context) error {
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
		SnapshotID:   snapshotID,
		AgentID:      app.config.AgentID,
		AgentVersion: app.version,
		CapturedAt:   now,
		Host:         host,
		Containers:   containers,
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
