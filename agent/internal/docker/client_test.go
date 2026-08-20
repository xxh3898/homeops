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
    "homeops.logs": "true",
    "homeops.notifications": "true",
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
	if !container.LogsAllowed {
		t.Fatal("LogsAllowed = false, want true")
	}
	if !container.NotificationsAllowed {
		t.Fatal("NotificationsAllowed = false, want true")
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
	if !contains(string(encoded), `"notificationsAllowed":true`) {
		t.Fatalf("encoded container = %s, want bounded notification capability", encoded)
	}
}

func TestMapListedRequiresExactNotificationOptIn(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name  string
		key   string
		value string
		want  bool
	}{
		{name: "exact true", key: "homeops.notifications", value: "true", want: true},
		{name: "uppercase key", key: "HOMEOPS.NOTIFICATIONS", value: "true"},
		{name: "mixed case key", key: "homeops.Notifications", value: "true"},
		{name: "uppercase value", key: "homeops.notifications", value: "TRUE"},
		{name: "title case value", key: "homeops.notifications", value: "True"},
		{name: "leading whitespace", key: "homeops.notifications", value: " true"},
		{name: "trailing whitespace", key: "homeops.notifications", value: "true "},
		{name: "numeric truthy", key: "homeops.notifications", value: "1"},
		{name: "word truthy", key: "homeops.notifications", value: "yes"},
		{name: "empty", key: "homeops.notifications", value: ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			container := mapListed(listedContainer{Labels: map[string]string{
				test.key: test.value,
			}})

			if container.NotificationsAllowed != test.want {
				t.Fatalf(
					"NotificationsAllowed = %v, want %v for %q",
					container.NotificationsAllowed,
					test.want,
					test.value)
			}
		})
	}
}

func TestMapListedRequiresExactManagedOptIn(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name  string
		key   string
		value string
		want  bool
	}{
		{name: "exact true", key: "homeops.managed", value: "true", want: true},
		{name: "uppercase key", key: "HOMEOPS.MANAGED", value: "true"},
		{name: "mixed case key", key: "homeops.Managed", value: "true"},
		{name: "uppercase value", key: "homeops.managed", value: "TRUE"},
		{name: "title case value", key: "homeops.managed", value: "True"},
		{name: "leading whitespace", key: "homeops.managed", value: " true"},
		{name: "trailing whitespace", key: "homeops.managed", value: "true "},
		{name: "numeric truthy", key: "homeops.managed", value: "1"},
		{name: "word truthy", key: "homeops.managed", value: "yes"},
		{name: "empty", key: "homeops.managed", value: ""},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			container := mapListed(listedContainer{Labels: map[string]string{
				test.key: test.value,
			}})

			if container.Managed != test.want {
				t.Fatalf(
					"Managed = %v, want %v for %q",
					container.Managed,
					test.want,
					test.value)
			}
		})
	}
}

func TestMapListedKeepsContainerAuthoritiesIndependent(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name                 string
		labels               map[string]string
		managed              bool
		logsAllowed          bool
		notificationsAllowed bool
	}{
		{name: "none", labels: map[string]string{}},
		{
			name:    "managed only",
			labels:  map[string]string{"homeops.managed": "true"},
			managed: true,
		},
		{
			name:        "logs only",
			labels:      map[string]string{"homeops.logs": "true"},
			logsAllowed: true,
		},
		{
			name:                 "notifications only",
			labels:               map[string]string{"homeops.notifications": "true"},
			notificationsAllowed: true,
		},
		{
			name: "all",
			labels: map[string]string{
				"homeops.managed":       "true",
				"homeops.logs":          "true",
				"homeops.notifications": "true",
			},
			managed:              true,
			logsAllowed:          true,
			notificationsAllowed: true,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			container := mapListed(listedContainer{Labels: test.labels})

			if container.Managed != test.managed ||
				container.LogsAllowed != test.logsAllowed ||
				container.NotificationsAllowed != test.notificationsAllowed {
				t.Fatalf(
					"authorities = managed:%v logs:%v notifications:%v",
					container.Managed,
					container.LogsAllowed,
					container.NotificationsAllowed)
			}
		})
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
