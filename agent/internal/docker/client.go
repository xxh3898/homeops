package docker

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/xxh3898/homeops/agent/internal/snapshot"
)

const (
	maximumVersionResponse = 256 * 1024
	maximumListResponse    = 4 * 1024 * 1024
	maximumInspectResponse = 1024 * 1024
	maximumStatsResponse   = 1024 * 1024
)

var apiVersionPattern = regexp.MustCompile(`^[0-9]+\.[0-9]+$`)

type Client struct {
	httpClient         *http.Client
	cpuSampleMutex     sync.Mutex
	previousCPUSamples map[string]cpuSample
}

func NewClient(socketPath string) (*Client, error) {
	info, err := os.Stat(socketPath)
	if err != nil {
		return nil, fmt.Errorf("inspect Docker socket: %w", err)
	}
	if info.Mode()&os.ModeSocket == 0 {
		return nil, errors.New("Docker endpoint is not a Unix socket")
	}
	dialer := &net.Dialer{Timeout: 3 * time.Second}
	transport := &http.Transport{
		DialContext: func(
			ctx context.Context,
			_ string,
			_ string,
		) (net.Conn, error) {
			return dialer.DialContext(ctx, "unix", socketPath)
		},
		DisableCompression: true,
		MaxIdleConns:       2,
		IdleConnTimeout:    30 * time.Second,
	}
	return &Client{
		httpClient: &http.Client{
			Transport: transport,
			Timeout:   10 * time.Second,
		},
		previousCPUSamples: make(map[string]cpuSample),
	}, nil
}

func (client *Client) Containers(
	ctx context.Context,
	maximum int,
) ([]snapshot.Container, error) {
	version, err := client.apiVersion(ctx)
	if err != nil {
		return nil, err
	}
	var listed []listedContainer
	if err := client.getJSON(
		ctx,
		"/v"+version+"/containers/json?all=1",
		maximumListResponse,
		&listed); err != nil {
		return nil, fmt.Errorf("list Docker containers: %w", err)
	}
	if len(listed) > maximum {
		return nil, fmt.Errorf(
			"Docker container count %d exceeds configured maximum %d",
			len(listed),
			maximum)
	}
	sort.Slice(listed, func(left int, right int) bool {
		return primaryName(listed[left].Names) < primaryName(listed[right].Names)
	})

	containers := make([]snapshot.Container, 0, len(listed))
	activeContainerIDs := make(map[string]struct{}, len(listed))
	for _, item := range listed {
		activeContainerIDs[item.ID] = struct{}{}
		container := mapListed(item)
		var inspected inspectedContainer
		path := "/v" + version + "/containers/" + url.PathEscape(item.ID) + "/json"
		if err := client.getJSON(
			ctx,
			path,
			maximumInspectResponse,
			&inspected); err == nil {
			container.Health = normalizeHealth(inspected.State.Health.Status)
			container.RestartCount = int64(inspected.RestartCount)
			if startedAt, parseErr := time.Parse(
				time.RFC3339Nano,
				inspected.State.StartedAt); parseErr == nil && !startedAt.IsZero() {
				container.StartedAt = &startedAt
			}
		}
		var stats containerStats
		statsPath := "/v" + version + "/containers/" +
			url.PathEscape(item.ID) + "/stats?stream=false&one-shot=1"
		if err := client.getJSON(
			ctx,
			statsPath,
			maximumStatsResponse,
			&stats); err == nil {
			container.CPUUsagePercent = client.calculateCPUPercent(
				item.ID,
				stats)
			container.MemoryUsage, container.MemoryLimit = calculateMemory(stats)
		}
		containers = append(containers, container)
	}
	client.removeInactiveCPUSamples(activeContainerIDs)
	return containers, nil
}

func (client *Client) apiVersion(ctx context.Context) (string, error) {
	var response versionResponse
	if err := client.getJSON(
		ctx,
		"/version",
		maximumVersionResponse,
		&response); err != nil {
		return "", fmt.Errorf("read Docker API version: %w", err)
	}
	if !apiVersionPattern.MatchString(response.APIVersion) {
		return "", errors.New("Docker API version has an unexpected format")
	}
	return response.APIVersion, nil
}

func (client *Client) getJSON(
	ctx context.Context,
	path string,
	maximumBytes int64,
	target any,
) error {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		"http://docker"+path,
		nil)
	if err != nil {
		return err
	}
	response, err := client.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return fmt.Errorf("Docker API returned status %d", response.StatusCode)
	}
	decoder := json.NewDecoder(io.LimitReader(response.Body, maximumBytes))
	if err := decoder.Decode(target); err != nil {
		return err
	}
	return nil
}

func mapListed(item listedContainer) snapshot.Container {
	return snapshot.Container{
		ID:             item.ID,
		Name:           truncate(primaryName(item.Names), 128),
		ComposeProject: truncate(item.Labels["com.docker.compose.project"], 128),
		Image:          truncate(item.Image, 512),
		State:          normalizeState(item.State),
		Health:         "UNKNOWN",
		Status:         truncate(item.Status, 512),
		Ports:          mapPorts(item.Ports),
		Managed:        strings.EqualFold(item.Labels["homeops.managed"], "true"),
	}
}

func mapPorts(ports []listedPort) []snapshot.ContainerPort {
	mapped := make([]snapshot.ContainerPort, 0, min(len(ports), 64))
	for _, port := range ports {
		if len(mapped) == 64 {
			break
		}
		if port.PrivatePort < 1 || port.PrivatePort > 65535 {
			continue
		}
		portType := strings.ToUpper(port.Type)
		switch portType {
		case "TCP", "UDP", "SCTP":
		default:
			portType = "UNKNOWN"
		}
		var publicPort *uint16
		if port.PublicPort >= 1 && port.PublicPort <= 65535 {
			value := uint16(port.PublicPort)
			publicPort = &value
		}
		mapped = append(mapped, snapshot.ContainerPort{
			PrivatePort: uint16(port.PrivatePort),
			PublicPort:  publicPort,
			Type:        portType,
		})
	}
	return mapped
}

func primaryName(names []string) string {
	if len(names) == 0 {
		return "unknown"
	}
	return strings.TrimPrefix(names[0], "/")
}

func normalizeState(value string) string {
	switch strings.ToLower(value) {
	case "created", "running", "paused", "restarting", "removing", "exited", "dead":
		return strings.ToUpper(value)
	default:
		return "UNKNOWN"
	}
}

func normalizeHealth(value string) string {
	switch strings.ToLower(value) {
	case "healthy", "unhealthy", "starting":
		return strings.ToUpper(value)
	case "":
		return "NONE"
	default:
		return "UNKNOWN"
	}
}

func truncate(value string, maximum int) string {
	if len(value) <= maximum {
		return value
	}
	return value[:maximum]
}

func (client *Client) calculateCPUPercent(
	containerID string,
	stats containerStats,
) *float64 {
	current := cpuSample{
		containerUsage: stats.CPUStats.CPUUsage.TotalUsage,
		systemUsage:    stats.CPUStats.SystemUsage,
		onlineCPUs:     stats.CPUStats.OnlineCPUs,
	}
	if current.onlineCPUs == 0 {
		current.onlineCPUs = uint64(
			len(stats.CPUStats.CPUUsage.PerCPUUsage))
	}
	client.cpuSampleMutex.Lock()
	previous, available := client.previousCPUSamples[containerID]
	client.previousCPUSamples[containerID] = current
	client.cpuSampleMutex.Unlock()
	if !available {
		return nil
	}
	return cpuPercentFromSamples(previous, current)
}

func cpuPercentFromSamples(previous cpuSample, current cpuSample) *float64 {
	currentCPU := current.containerUsage
	previousCPU := previous.containerUsage
	currentSystem := current.systemUsage
	previousSystem := previous.systemUsage
	if currentCPU <= previousCPU || currentSystem <= previousSystem {
		return nil
	}
	onlineCPUs := current.onlineCPUs
	if onlineCPUs == 0 {
		return nil
	}
	cpuDelta := float64(currentCPU - previousCPU)
	systemDelta := float64(currentSystem - previousSystem)
	percent := (cpuDelta / systemDelta) * float64(onlineCPUs) * 100
	if math.IsNaN(percent) || math.IsInf(percent, 0) || percent < 0 {
		return nil
	}
	percent = math.Round(percent*100) / 100
	return &percent
}

func (client *Client) removeInactiveCPUSamples(active map[string]struct{}) {
	client.cpuSampleMutex.Lock()
	defer client.cpuSampleMutex.Unlock()
	for containerID := range client.previousCPUSamples {
		if _, exists := active[containerID]; !exists {
			delete(client.previousCPUSamples, containerID)
		}
	}
}

func calculateMemory(stats containerStats) (*uint64, *uint64) {
	usage := stats.MemoryStats.Usage
	cache := stats.MemoryStats.Stats.InactiveFile
	if cache == 0 {
		cache = stats.MemoryStats.Stats.TotalInactiveFile
	}
	if cache == 0 {
		cache = stats.MemoryStats.Stats.Cache
	}
	if cache <= usage {
		usage -= cache
	}
	limit := stats.MemoryStats.Limit
	if limit == 0 {
		return &usage, nil
	}
	return &usage, &limit
}

type versionResponse struct {
	APIVersion string `json:"ApiVersion"`
}

type listedContainer struct {
	ID     string            `json:"Id"`
	Names  []string          `json:"Names"`
	Image  string            `json:"Image"`
	State  string            `json:"State"`
	Status string            `json:"Status"`
	Labels map[string]string `json:"Labels"`
	Ports  []listedPort      `json:"Ports"`
}

type listedPort struct {
	PrivatePort int    `json:"PrivatePort"`
	PublicPort  int    `json:"PublicPort"`
	Type        string `json:"Type"`
}

type cpuSample struct {
	containerUsage uint64
	systemUsage    uint64
	onlineCPUs     uint64
}

type inspectedContainer struct {
	RestartCount int `json:"RestartCount"`
	State        struct {
		StartedAt string `json:"StartedAt"`
		Health    struct {
			Status string `json:"Status"`
		} `json:"Health"`
	} `json:"State"`
}

type containerStats struct {
	CPUStats struct {
		SystemUsage uint64 `json:"system_cpu_usage"`
		OnlineCPUs  uint64 `json:"online_cpus"`
		CPUUsage    struct {
			TotalUsage  uint64   `json:"total_usage"`
			PerCPUUsage []uint64 `json:"percpu_usage"`
		} `json:"cpu_usage"`
	} `json:"cpu_stats"`
	MemoryStats struct {
		Usage uint64 `json:"usage"`
		Limit uint64 `json:"limit"`
		Stats struct {
			InactiveFile      uint64 `json:"inactive_file"`
			TotalInactiveFile uint64 `json:"total_inactive_file"`
			Cache             uint64 `json:"cache"`
		} `json:"stats"`
	} `json:"memory_stats"`
}
