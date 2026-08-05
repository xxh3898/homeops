package main

import (
	"context"
	"log/slog"
	"os"
	"os/signal"
	"runtime"
	"syscall"

	"github.com/xxh3898/homeops/agent/internal/app"
	"github.com/xxh3898/homeops/agent/internal/config"
)

var version = "dev"

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	}))
	if runtime.GOOS != "darwin" {
		logger.Error("HomeOps Agent supports macOS only")
		os.Exit(1)
	}
	configuration, err := config.Load()
	if err != nil {
		logger.Error("Agent configuration is invalid", "error", err)
		os.Exit(1)
	}
	agent, err := app.New(configuration, version, logger)
	if err != nil {
		logger.Error("Agent startup failed", "error", err)
		os.Exit(1)
	}
	ctx, stop := signal.NotifyContext(
		context.Background(),
		syscall.SIGINT,
		syscall.SIGTERM)
	defer stop()
	if err := agent.Run(ctx); err != nil {
		logger.Error("Agent stopped unexpectedly", "error", err)
		os.Exit(1)
	}
}
