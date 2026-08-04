package docker

import (
	"encoding/json"
	"testing"
)

func TestMapListedKeepsAllowlistedContainerFields(t *testing.T) {
	t.Parallel()
	raw := `{
  "Id": "0123456789abcdef",
  "Names": ["/example-api"],
  "Image": "example/api:sha-test",
  "State": "running",
  "Status": "Up 2 hours (healthy)",
  "Ports": [{"PrivatePort": 8080, "PublicPort": 13080, "Type": "tcp", "IP": "must-not-be-forwarded"}],
  "Labels": {
    "com.docker.compose.project": "example",
    "homeops.managed": "true",
    "secret-looking-label": "must-not-be-forwarded"
  }
}`
	var listed listedContainer
	if err := json.Unmarshal([]byte(raw), &listed); err != nil {
		t.Fatalf("decode fixture: %v", err)
	}

	container := mapListed(listed)

	if container.Name != "example-api" {
		t.Fatalf("Name = %q, want example-api", container.Name)
	}
	if container.ComposeProject != "example" {
		t.Fatalf("ComposeProject = %q, want example", container.ComposeProject)
	}
	if !container.Managed {
		t.Fatal("Managed = false, want true")
	}
	if len(container.Ports) != 1 ||
		container.Ports[0].PrivatePort != 8080 ||
		container.Ports[0].PublicPort == nil ||
		*container.Ports[0].PublicPort != 13080 {
		t.Fatalf("ports = %v, want one bounded TCP mapping", container.Ports)
	}
	encoded, err := json.Marshal(container)
	if err != nil {
		t.Fatalf("encode container: %v", err)
	}
	if string(encoded) == "" || contains(string(encoded), "must-not-be-forwarded") {
		t.Fatalf("encoded container leaked an unapproved label: %s", encoded)
	}
}

func TestNormalizeHealthDistinguishesMissingHealthcheck(t *testing.T) {
	t.Parallel()
	if got := normalizeHealth(""); got != "NONE" {
		t.Fatalf("normalizeHealth(\"\") = %q, want NONE", got)
	}
	if got := normalizeHealth("unhealthy"); got != "UNHEALTHY" {
		t.Fatalf("normalizeHealth(unhealthy) = %q, want UNHEALTHY", got)
	}
}

func TestCalculateCPUPercentUsesDockerDeltaFormula(t *testing.T) {
	t.Parallel()
	previous := cpuSample{
		containerUsage: 100,
		systemUsage:    1_000,
		onlineCPUs:     4,
	}
	current := cpuSample{
		containerUsage: 300,
		systemUsage:    1_800,
		onlineCPUs:     4,
	}

	percent := cpuPercentFromSamples(previous, current)

	if percent == nil || *percent != 100 {
		t.Fatalf("CPU percent = %v, want 100", percent)
	}
}

func TestCalculateCPUPercentNeedsPreviousAgentSample(t *testing.T) {
	t.Parallel()
	client := &Client{previousCPUSamples: make(map[string]cpuSample)}
	var stats containerStats
	stats.CPUStats.CPUUsage.TotalUsage = 300
	stats.CPUStats.SystemUsage = 1_800
	stats.CPUStats.OnlineCPUs = 4

	if percent := client.calculateCPUPercent("container-a", stats); percent != nil {
		t.Fatalf("first CPU percent = %v, want nil", percent)
	}
}

func TestCalculateMemorySubtractsInactiveFileCache(t *testing.T) {
	t.Parallel()
	var stats containerStats
	stats.MemoryStats.Usage = 1_000
	stats.MemoryStats.Limit = 2_000
	stats.MemoryStats.Stats.InactiveFile = 200

	usage, limit := calculateMemory(stats)

	if usage == nil || *usage != 800 {
		t.Fatalf("memory usage = %v, want 800", usage)
	}
	if limit == nil || *limit != 2_000 {
		t.Fatalf("memory limit = %v, want 2000", limit)
	}
}

func contains(value string, fragment string) bool {
	for index := 0; index+len(fragment) <= len(value); index++ {
		if value[index:index+len(fragment)] == fragment {
			return true
		}
	}
	return false
}
