package config

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

var agentIDPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`)

type Config struct {
	AgentID       string
	APIURL        string
	ClientCert    string
	ClientKey     string
	CACert        string
	DockerSocket  string
	SpoolDir      string
	VersionProof  string
	Interval      time.Duration
	MaxContainers int
	MaxSpoolFiles int
}

func Load() (Config, error) {
	interval, err := duration("HOMEOPS_AGENT_INTERVAL", 5*time.Second)
	if err != nil {
		return Config{}, err
	}
	maxContainers, err := integer("HOMEOPS_AGENT_MAXIMUM_CONTAINERS", 128)
	if err != nil {
		return Config{}, err
	}
	maxSpoolFiles, err := integer("HOMEOPS_AGENT_MAXIMUM_SPOOL_FILES", 120)
	if err != nil {
		return Config{}, err
	}

	config := Config{
		AgentID:       strings.TrimSpace(os.Getenv("HOMEOPS_AGENT_ID")),
		APIURL:        strings.TrimSpace(os.Getenv("HOMEOPS_AGENT_API_URL")),
		ClientCert:    strings.TrimSpace(os.Getenv("HOMEOPS_AGENT_CLIENT_CERT")),
		ClientKey:     strings.TrimSpace(os.Getenv("HOMEOPS_AGENT_CLIENT_KEY")),
		CACert:        strings.TrimSpace(os.Getenv("HOMEOPS_AGENT_CA_CERT")),
		DockerSocket:  strings.TrimSpace(os.Getenv("HOMEOPS_DOCKER_SOCKET")),
		SpoolDir:      strings.TrimSpace(os.Getenv("HOMEOPS_AGENT_SPOOL_DIR")),
		VersionProof:  strings.TrimSpace(os.Getenv("HOMEOPS_AGENT_VERSION_PROOF_FILE")),
		Interval:      interval,
		MaxContainers: maxContainers,
		MaxSpoolFiles: maxSpoolFiles,
	}
	return Validate(config)
}

func Validate(config Config) (Config, error) {
	if !agentIDPattern.MatchString(config.AgentID) {
		return Config{}, errors.New("HOMEOPS_AGENT_ID has an unexpected format")
	}
	parsedURL, err := url.Parse(config.APIURL)
	if err != nil || parsedURL.Scheme != "https" {
		return Config{}, errors.New("HOMEOPS_AGENT_API_URL must be an HTTPS URL")
	}
	if !loopbackHost(parsedURL.Hostname()) {
		return Config{}, errors.New("HOMEOPS_AGENT_API_URL must use a loopback host")
	}
	if parsedURL.Path != "/api/v1/internal/agent/snapshots" {
		return Config{}, errors.New("HOMEOPS_AGENT_API_URL path is not allowed")
	}
	if parsedURL.RawQuery != "" || parsedURL.Fragment != "" || parsedURL.User != nil {
		return Config{}, errors.New("HOMEOPS_AGENT_API_URL must not contain credentials, query, or fragment")
	}

	var pathErr error
	config.ClientCert, pathErr = absolutePath(config.ClientCert, "client certificate")
	if pathErr != nil {
		return Config{}, pathErr
	}
	config.ClientKey, pathErr = absolutePath(config.ClientKey, "client key")
	if pathErr != nil {
		return Config{}, pathErr
	}
	config.CACert, pathErr = absolutePath(config.CACert, "CA certificate")
	if pathErr != nil {
		return Config{}, pathErr
	}
	config.DockerSocket, pathErr = absolutePath(config.DockerSocket, "Docker socket")
	if pathErr != nil {
		return Config{}, pathErr
	}
	if filepath.Base(config.DockerSocket) != "docker.sock" {
		return Config{}, errors.New("Docker socket path must end with docker.sock")
	}
	config.SpoolDir, pathErr = absolutePath(config.SpoolDir, "spool directory")
	if pathErr != nil {
		return Config{}, pathErr
	}
	if config.VersionProof != "" {
		config.VersionProof, pathErr = absolutePath(
			config.VersionProof,
			"version proof file")
		if pathErr != nil {
			return Config{}, pathErr
		}
	}
	if config.Interval < 5*time.Second || config.Interval > 5*time.Minute {
		return Config{}, errors.New("agent interval must be between 5s and 5m")
	}
	if config.MaxContainers < 1 || config.MaxContainers > 256 {
		return Config{}, errors.New("maximum containers must be between 1 and 256")
	}
	if config.MaxSpoolFiles < 1 || config.MaxSpoolFiles > 1440 {
		return Config{}, errors.New("maximum spool files must be between 1 and 1440")
	}
	return config, nil
}

func loopbackHost(host string) bool {
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

func absolutePath(value string, label string) (string, error) {
	if value == "" {
		return "", fmt.Errorf("%s path is required", label)
	}
	if !filepath.IsAbs(value) {
		return "", fmt.Errorf("%s path must be absolute", label)
	}
	return filepath.Clean(value), nil
}

func duration(name string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("parse %s: %w", name, err)
	}
	return value, nil
}

func integer(name string, fallback int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("parse %s: %w", name, err)
	}
	return value, nil
}
