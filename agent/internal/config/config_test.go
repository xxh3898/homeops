package config

import (
	"path/filepath"
	"testing"
	"time"
)

func TestValidateAcceptsLoopbackConfiguration(t *testing.T) {
	t.Parallel()
	root := t.TempDir()
	configuration, err := Validate(Config{
		AgentID:       "local-mac",
		APIURL:        "https://127.0.0.1:13443/api/v1/internal/agent/snapshots",
		ClientCert:    filepath.Join(root, "agent.crt"),
		ClientKey:     filepath.Join(root, "agent.key"),
		CACert:        filepath.Join(root, "ca.crt"),
		DockerSocket:  filepath.Join(root, "docker.sock"),
		SpoolDir:      filepath.Join(root, "spool"),
		VersionProof:  filepath.Join(root, "version-proof"),
		Interval:      5 * time.Second,
		MaxContainers: 128,
		MaxSpoolFiles: 120,
	})

	if err != nil {
		t.Fatalf("Validate returned an error: %v", err)
	}
	if configuration.AgentID != "local-mac" {
		t.Fatalf("AgentID = %q, want local-mac", configuration.AgentID)
	}
}

func TestValidateRejectsRelativeVersionProofPath(t *testing.T) {
	t.Parallel()
	root := t.TempDir()
	_, err := Validate(Config{
		AgentID:       "local-mac",
		APIURL:        "https://localhost:13443/api/v1/internal/agent/snapshots",
		ClientCert:    filepath.Join(root, "agent.crt"),
		ClientKey:     filepath.Join(root, "agent.key"),
		CACert:        filepath.Join(root, "ca.crt"),
		DockerSocket:  filepath.Join(root, "docker.sock"),
		SpoolDir:      filepath.Join(root, "spool"),
		VersionProof:  "version-proof",
		Interval:      5 * time.Second,
		MaxContainers: 128,
		MaxSpoolFiles: 120,
	})
	if err == nil || err.Error() != "version proof file path must be absolute" {
		t.Fatalf("error = %v, want version proof path error", err)
	}
}

func TestValidateRejectsNonLoopbackEndpoint(t *testing.T) {
	t.Parallel()
	root := t.TempDir()
	_, err := Validate(Config{
		AgentID:       "local-mac",
		APIURL:        "https://example.invalid/api/v1/internal/agent/snapshots",
		ClientCert:    filepath.Join(root, "agent.crt"),
		ClientKey:     filepath.Join(root, "agent.key"),
		CACert:        filepath.Join(root, "ca.crt"),
		DockerSocket:  filepath.Join(root, "docker.sock"),
		SpoolDir:      filepath.Join(root, "spool"),
		Interval:      5 * time.Second,
		MaxContainers: 128,
		MaxSpoolFiles: 120,
	})

	if err == nil || err.Error() != "HOMEOPS_AGENT_API_URL must use a loopback host" {
		t.Fatalf("error = %v, want loopback validation error", err)
	}
}

func TestValidateRejectsArbitraryDockerEndpoint(t *testing.T) {
	t.Parallel()
	root := t.TempDir()
	_, err := Validate(Config{
		AgentID:       "local-mac",
		APIURL:        "https://localhost:13443/api/v1/internal/agent/snapshots",
		ClientCert:    filepath.Join(root, "agent.crt"),
		ClientKey:     filepath.Join(root, "agent.key"),
		CACert:        filepath.Join(root, "ca.crt"),
		DockerSocket:  filepath.Join(root, "other.sock"),
		SpoolDir:      filepath.Join(root, "spool"),
		Interval:      5 * time.Second,
		MaxContainers: 128,
		MaxSpoolFiles: 120,
	})

	if err == nil || err.Error() != "Docker socket path must end with docker.sock" {
		t.Fatalf("error = %v, want Docker socket validation error", err)
	}
}

func TestValidateRejectsRelativeSecretPath(t *testing.T) {
	t.Parallel()
	root := t.TempDir()
	_, err := Validate(Config{
		AgentID:       "local-mac",
		APIURL:        "https://localhost:13443/api/v1/internal/agent/snapshots",
		ClientCert:    "agent.crt",
		ClientKey:     filepath.Join(root, "agent.key"),
		CACert:        filepath.Join(root, "ca.crt"),
		DockerSocket:  filepath.Join(root, "docker.sock"),
		SpoolDir:      filepath.Join(root, "spool"),
		Interval:      5 * time.Second,
		MaxContainers: 128,
		MaxSpoolFiles: 120,
	})

	if err == nil || err.Error() != "client certificate path must be absolute" {
		t.Fatalf("error = %v, want absolute path validation error", err)
	}
}

func TestValidateRejectsQueryOnAgentEndpoint(t *testing.T) {
	t.Parallel()
	root := t.TempDir()
	_, err := Validate(Config{
		AgentID:       "local-mac",
		APIURL:        "https://localhost:13443/api/v1/internal/agent/snapshots?target=other",
		ClientCert:    filepath.Join(root, "agent.crt"),
		ClientKey:     filepath.Join(root, "agent.key"),
		CACert:        filepath.Join(root, "ca.crt"),
		DockerSocket:  filepath.Join(root, "docker.sock"),
		SpoolDir:      filepath.Join(root, "spool"),
		Interval:      5 * time.Second,
		MaxContainers: 128,
		MaxSpoolFiles: 120,
	})

	if err == nil ||
		err.Error() != "HOMEOPS_AGENT_API_URL must not contain credentials, query, or fragment" {
		t.Fatalf("error = %v, want query validation error", err)
	}
}
