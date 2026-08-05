package collector

import (
	"context"
	"errors"
	"fmt"
	"math"
	"os/exec"
	"regexp"
	"runtime"
	"strconv"
	"strings"
	"syscall"

	"github.com/xxh3898/homeops/agent/internal/snapshot"
)

var (
	cpuIdlePattern  = regexp.MustCompile(`([0-9]+(?:\.[0-9]+)?)% idle`)
	bootTimePattern = regexp.MustCompile(`sec = ([0-9]+)`)
	pageSizePattern = regexp.MustCompile(`page size of ([0-9]+) bytes`)
	pageLinePattern = regexp.MustCompile(`^Pages ([^:]+):\s+([0-9]+)\.$`)
)

type CommandRunner interface {
	Output(context.Context, string, ...string) ([]byte, error)
}

type ExecRunner struct{}

func (ExecRunner) Output(
	ctx context.Context,
	name string,
	args ...string,
) ([]byte, error) {
	return exec.CommandContext(ctx, name, args...).Output()
}

type HostCollector struct {
	runner CommandRunner
}

func NewHostCollector(runner CommandRunner) *HostCollector {
	return &HostCollector{runner: runner}
}

func (collector *HostCollector) Collect(ctx context.Context) (snapshot.Host, error) {
	if runtime.GOOS != "darwin" {
		return snapshot.Host{}, errors.New("host collector supports macOS only")
	}
	cpu, err := collector.cpu(ctx)
	if err != nil {
		return snapshot.Host{}, err
	}
	memoryTotal, memoryUsed, err := collector.memory(ctx)
	if err != nil {
		return snapshot.Host{}, err
	}
	diskTotal, diskUsed, err := disk("/")
	if err != nil {
		return snapshot.Host{}, err
	}
	uptime, err := collector.uptime(ctx)
	if err != nil {
		return snapshot.Host{}, err
	}
	return snapshot.Host{
		CPUUsagePercent: cpu,
		MemoryTotal:     memoryTotal,
		MemoryUsed:      memoryUsed,
		DiskTotal:       diskTotal,
		DiskUsed:        diskUsed,
		UptimeSeconds:   uptime,
	}, nil
}

func (collector *HostCollector) cpu(ctx context.Context) (float64, error) {
	output, err := collector.runner.Output(
		ctx,
		"/usr/bin/top",
		"-l", "2",
		"-n", "0",
		"-F",
		"-R",
		"-stats", "cpu")
	if err != nil {
		return 0, fmt.Errorf("collect CPU usage: %w", err)
	}
	return parseCPUUsage(string(output))
}

func (collector *HostCollector) memory(ctx context.Context) (uint64, uint64, error) {
	totalOutput, err := collector.runner.Output(
		ctx,
		"/usr/sbin/sysctl",
		"-n",
		"hw.memsize")
	if err != nil {
		return 0, 0, fmt.Errorf("collect total memory: %w", err)
	}
	total, err := strconv.ParseUint(strings.TrimSpace(string(totalOutput)), 10, 64)
	if err != nil {
		return 0, 0, fmt.Errorf("parse total memory: %w", err)
	}
	vmOutput, err := collector.runner.Output(ctx, "/usr/bin/vm_stat")
	if err != nil {
		return 0, 0, fmt.Errorf("collect memory usage: %w", err)
	}
	used, err := parseMemoryUsed(string(vmOutput), total)
	if err != nil {
		return 0, 0, err
	}
	return total, used, nil
}

func (collector *HostCollector) uptime(ctx context.Context) (uint64, error) {
	output, err := collector.runner.Output(
		ctx,
		"/usr/sbin/sysctl",
		"-n",
		"kern.boottime")
	if err != nil {
		return 0, fmt.Errorf("collect boot time: %w", err)
	}
	matches := bootTimePattern.FindStringSubmatch(string(output))
	if len(matches) != 2 {
		return 0, errors.New("parse boot time: unexpected output")
	}
	bootSeconds, err := strconv.ParseInt(matches[1], 10, 64)
	if err != nil {
		return 0, fmt.Errorf("parse boot time: %w", err)
	}
	var now syscall.Timeval
	if err := syscall.Gettimeofday(&now); err != nil {
		return 0, fmt.Errorf("read current time: %w", err)
	}
	nowSeconds := now.Sec
	if bootSeconds > nowSeconds {
		return 0, errors.New("boot time is in the future")
	}
	return uint64(nowSeconds - bootSeconds), nil
}

func parseCPUUsage(output string) (float64, error) {
	matches := cpuIdlePattern.FindAllStringSubmatch(output, -1)
	if len(matches) == 0 {
		return 0, errors.New("parse CPU usage: idle percentage not found")
	}
	idle, err := strconv.ParseFloat(matches[len(matches)-1][1], 64)
	if err != nil {
		return 0, fmt.Errorf("parse CPU idle: %w", err)
	}
	usage := math.Max(0, math.Min(100, 100-idle))
	return math.Round(usage*100) / 100, nil
}

func parseMemoryUsed(output string, total uint64) (uint64, error) {
	pageSizeMatch := pageSizePattern.FindStringSubmatch(output)
	if len(pageSizeMatch) != 2 {
		return 0, errors.New("parse memory usage: page size not found")
	}
	pageSize, err := strconv.ParseUint(pageSizeMatch[1], 10, 64)
	if err != nil {
		return 0, fmt.Errorf("parse memory page size: %w", err)
	}
	if pageSize == 0 {
		return 0, errors.New("parse memory usage: page size must be positive")
	}

	usedPagesByName := make(map[string]uint64, 2)
	for _, line := range strings.Split(output, "\n") {
		match := pageLinePattern.FindStringSubmatch(strings.TrimSpace(line))
		if len(match) != 3 {
			continue
		}
		name := strings.TrimSpace(match[1])
		if name != "active" && name != "wired down" {
			continue
		}
		if _, exists := usedPagesByName[name]; exists {
			return 0, fmt.Errorf("parse memory usage: duplicate Pages %s", name)
		}
		pages, parseErr := strconv.ParseUint(match[2], 10, 64)
		if parseErr != nil {
			return 0, fmt.Errorf("parse memory pages: %w", parseErr)
		}
		usedPagesByName[name] = pages
	}

	activePages, hasActive := usedPagesByName["active"]
	wiredPages, hasWired := usedPagesByName["wired down"]
	if !hasActive || !hasWired {
		return 0, errors.New("parse memory usage: active or wired pages not found")
	}
	if activePages > ^uint64(0)-wiredPages {
		return 0, errors.New("parse memory usage: used page count overflows")
	}
	usedPages := activePages + wiredPages
	if usedPages > ^uint64(0)/pageSize {
		return 0, errors.New("parse memory usage: used byte count overflows")
	}
	usedBytes := usedPages * pageSize
	if usedBytes > total {
		return 0, errors.New("parse memory usage: used memory exceeds total")
	}
	return usedBytes, nil
}

func disk(path string) (uint64, uint64, error) {
	var stats syscall.Statfs_t
	if err := syscall.Statfs(path, &stats); err != nil {
		return 0, 0, fmt.Errorf("collect disk usage: %w", err)
	}
	total := uint64(stats.Blocks) * uint64(stats.Bsize)
	available := uint64(stats.Bavail) * uint64(stats.Bsize)
	if available > total {
		return 0, 0, errors.New("disk available bytes exceed total bytes")
	}
	return total, total - available, nil
}
