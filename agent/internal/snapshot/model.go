package snapshot

import "time"

type Snapshot struct {
	SnapshotID            string      `json:"snapshotId"`
	AgentID               string      `json:"agentId"`
	AgentVersion          string      `json:"agentVersion"`
	CapturedAt            time.Time   `json:"capturedAt"`
	SupportsContainerLogs bool        `json:"supportsContainerLogs"`
	Host                  Host        `json:"host"`
	Containers            []Container `json:"containers"`
}

type Host struct {
	CPUUsagePercent float64 `json:"cpuUsagePercent"`
	MemoryTotal     uint64  `json:"memoryTotalBytes"`
	MemoryUsed      uint64  `json:"memoryUsedBytes"`
	DiskTotal       uint64  `json:"diskTotalBytes"`
	DiskUsed        uint64  `json:"diskUsedBytes"`
	UptimeSeconds   uint64  `json:"uptimeSeconds"`
}

type Container struct {
	ID              string          `json:"id"`
	Name            string          `json:"name"`
	ComposeProject  string          `json:"composeProject,omitempty"`
	Image           string          `json:"image"`
	State           string          `json:"state"`
	Health          string          `json:"health"`
	Status          string          `json:"status,omitempty"`
	StartedAt       *time.Time      `json:"startedAt,omitempty"`
	RestartCount    int64           `json:"restartCount"`
	CPUUsagePercent *float64        `json:"cpuUsagePercent,omitempty"`
	MemoryUsage     *uint64         `json:"memoryUsageBytes,omitempty"`
	MemoryLimit     *uint64         `json:"memoryLimitBytes,omitempty"`
	Ports           []ContainerPort `json:"ports"`
	Managed         bool            `json:"managed"`
	LogsAllowed     bool            `json:"logsAllowed"`
}

type ContainerPort struct {
	PrivatePort uint16  `json:"privatePort"`
	PublicPort  *uint16 `json:"publicPort,omitempty"`
	Type        string  `json:"type"`
}
