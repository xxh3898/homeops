package transport

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"time"

	"github.com/xxh3898/homeops/agent/internal/containercontrol"
	"github.com/xxh3898/homeops/agent/internal/containerlog"
	"github.com/xxh3898/homeops/agent/internal/recovery"
)

type Client struct {
	snapshotEndpoint       string
	logWorkEndpoint        string
	logResultEndpoint      string
	controlWorkEndpoint    string
	controlResultEndpoint  string
	recoveryWorkEndpoint   string
	recoveryResultEndpoint string
	httpClient             *http.Client
	now                    func() time.Time
}

const (
	snapshotPath                      = "/api/v1/internal/agent/snapshots"
	logWorkPath                       = "/api/v1/internal/agent/log-requests/next"
	logResultPath                     = "/api/v1/internal/agent/log-results"
	controlWorkPath                   = "/api/v1/internal/agent/control-requests/next"
	controlResultPath                 = "/api/v1/internal/agent/control-results"
	recoveryWorkPath                  = "/api/v1/internal/agent/recovery-requests/next"
	recoveryResultPath                = "/api/v1/internal/agent/recovery-results"
	maximumWorkResponseBytes          = 16 * 1024
	maximumResultPayloadBytes         = 192 * 1024
	maximumControlWorkResponseBytes   = 16 * 1024
	maximumControlResultPayloadBytes  = 4 * 1024
	maximumRecoveryWorkResponseBytes  = 4 * 1024
	maximumRecoveryResultPayloadBytes = 4 * 1024
)

func NewClient(
	endpoint string,
	clientCertPath string,
	clientKeyPath string,
	caCertPath string,
) (*Client, error) {
	clientCertificate, err := tls.LoadX509KeyPair(
		clientCertPath,
		clientKeyPath)
	if err != nil {
		return nil, fmt.Errorf("load Agent client certificate: %w", err)
	}
	caPEM, err := os.ReadFile(caCertPath)
	if err != nil {
		return nil, fmt.Errorf("read Agent CA certificate: %w", err)
	}
	caPool := x509.NewCertPool()
	if !caPool.AppendCertsFromPEM(caPEM) {
		return nil, errors.New("Agent CA certificate is invalid")
	}
	snapshotEndpoint, logWorkEndpoint, logResultEndpoint,
		controlWorkEndpoint, controlResultEndpoint, err :=
		deriveEndpoints(endpoint)
	if err != nil {
		return nil, err
	}
	recoveryWorkEndpoint, recoveryResultEndpoint, err :=
		deriveRecoveryEndpoints(endpoint)
	if err != nil {
		return nil, err
	}
	transport := &http.Transport{
		TLSClientConfig: &tls.Config{
			MinVersion:   tls.VersionTLS13,
			RootCAs:      caPool,
			Certificates: []tls.Certificate{clientCertificate},
		},
		DisableCompression: true,
		MaxIdleConns:       2,
		IdleConnTimeout:    30 * time.Second,
	}
	return &Client{
		snapshotEndpoint:       snapshotEndpoint,
		logWorkEndpoint:        logWorkEndpoint,
		logResultEndpoint:      logResultEndpoint,
		controlWorkEndpoint:    controlWorkEndpoint,
		controlResultEndpoint:  controlResultEndpoint,
		recoveryWorkEndpoint:   recoveryWorkEndpoint,
		recoveryResultEndpoint: recoveryResultEndpoint,
		httpClient: &http.Client{
			Transport: transport,
			Timeout:   10 * time.Second,
		},
		now: func() time.Time { return time.Now().UTC() },
	}, nil
}

func (client *Client) Send(ctx context.Context, payload []byte) error {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		client.snapshotEndpoint,
		bytes.NewReader(payload))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 16*1024))
	if response.StatusCode != http.StatusAccepted {
		return StatusError{StatusCode: response.StatusCode}
	}
	return nil
}

func (client *Client) NextContainerLogWork(
	ctx context.Context,
) (*containerlog.Work, error) {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		client.logWorkEndpoint,
		nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNoContent {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return nil, nil
	}
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return nil, StatusError{StatusCode: response.StatusCode}
	}
	payload, err := io.ReadAll(io.LimitReader(
		response.Body,
		maximumWorkResponseBytes+1))
	if err != nil {
		return nil, err
	}
	if len(payload) > maximumWorkResponseBytes {
		return nil, errors.New("container log work response is too large")
	}
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	var work containerlog.Work
	if err := decoder.Decode(&work); err != nil {
		return nil, errors.New("container log work response is invalid")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, errors.New("container log work response has trailing data")
	}
	if err := work.Validate(client.currentTime()); err != nil {
		return nil, err
	}
	return &work, nil
}

func (client *Client) currentTime() time.Time {
	if client.now != nil {
		return client.now().UTC()
	}
	return time.Now().UTC()
}

func (client *Client) SendContainerLogResult(
	ctx context.Context,
	result containerlog.Result,
) error {
	payload, err := json.Marshal(result)
	if err != nil {
		return errors.New("encode container log result")
	}
	if len(payload) > maximumResultPayloadBytes {
		return errors.New("container log result is too large")
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		client.logResultEndpoint,
		bytes.NewReader(payload))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 16*1024))
	if response.StatusCode != http.StatusNoContent {
		return StatusError{StatusCode: response.StatusCode}
	}
	return nil
}

func (client *Client) NextContainerControlWork(
	ctx context.Context,
) (*containercontrol.Work, error) {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		client.controlWorkEndpoint,
		nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNoContent {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return nil, nil
	}
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return nil, StatusError{StatusCode: response.StatusCode}
	}
	payload, err := io.ReadAll(io.LimitReader(
		response.Body,
		maximumControlWorkResponseBytes+1))
	if err != nil {
		return nil, err
	}
	if len(payload) > maximumControlWorkResponseBytes {
		return nil, errors.New("container control work response is too large")
	}
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	var work containercontrol.Work
	if err := decoder.Decode(&work); err != nil {
		return nil, errors.New("container control work response is invalid")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, errors.New("container control work response has trailing data")
	}
	if err := work.Validate(client.currentTime()); err != nil {
		return nil, err
	}
	return &work, nil
}

func (client *Client) SendContainerControlResult(
	ctx context.Context,
	result containercontrol.Result,
) error {
	if err := result.Validate(); err != nil {
		return errors.New("container control result is invalid")
	}
	payload, err := json.Marshal(result)
	if err != nil {
		return errors.New("encode container control result")
	}
	if len(payload) > maximumControlResultPayloadBytes {
		return errors.New("container control result is too large")
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		client.controlResultEndpoint,
		bytes.NewReader(payload))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
	if response.StatusCode != http.StatusNoContent {
		return StatusError{StatusCode: response.StatusCode}
	}
	return nil
}

func (client *Client) NextAutomaticRecoveryWork(
	ctx context.Context,
) (*recovery.Work, error) {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		client.recoveryWorkEndpoint,
		nil)
	if err != nil {
		return nil, err
	}
	request.Header.Set("Accept", "application/json")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode == http.StatusNoContent {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return nil, nil
	}
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return nil, StatusError{StatusCode: response.StatusCode}
	}
	payload, err := io.ReadAll(io.LimitReader(
		response.Body,
		maximumRecoveryWorkResponseBytes+1))
	if err != nil {
		return nil, err
	}
	if len(payload) > maximumRecoveryWorkResponseBytes {
		return nil, errors.New("automatic recovery work response is too large")
	}
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	var work recovery.Work
	if err := decoder.Decode(&work); err != nil {
		return nil, errors.New("automatic recovery work response is invalid")
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return nil, errors.New("automatic recovery work response has trailing data")
	}
	if err := work.Validate(client.currentTime()); err != nil {
		return nil, err
	}
	return &work, nil
}

func (client *Client) SendAutomaticRecoveryResult(
	ctx context.Context,
	result recovery.Result,
) error {
	if err := result.Validate(); err != nil {
		return errors.New("automatic recovery result is invalid")
	}
	payload, err := json.Marshal(result)
	if err != nil {
		return errors.New("encode automatic recovery result")
	}
	if len(payload) > maximumRecoveryResultPayloadBytes {
		return errors.New("automatic recovery result is too large")
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		client.recoveryResultEndpoint,
		bytes.NewReader(payload))
	if err != nil {
		return err
	}
	request.Header.Set("Content-Type", "application/json")
	response, err := client.httpClient.Do(request)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
	if response.StatusCode != http.StatusNoContent {
		return StatusError{StatusCode: response.StatusCode}
	}
	return nil
}

func deriveEndpoints(endpoint string) (string, string, string, string, string, error) {
	parsed, err := parseSnapshotEndpoint(endpoint)
	if err != nil {
		return "", "", "", "", "", errors.New("Agent API endpoint is invalid")
	}
	withPath := func(path string) string {
		derived := *parsed
		derived.Path = path
		derived.RawPath = ""
		return derived.String()
	}
	return withPath(snapshotPath),
		withPath(logWorkPath),
		withPath(logResultPath),
		withPath(controlWorkPath),
		withPath(controlResultPath),
		nil
}

func deriveRecoveryEndpoints(endpoint string) (string, string, error) {
	parsed, err := parseSnapshotEndpoint(endpoint)
	if err != nil {
		return "", "", errors.New("Agent API endpoint is invalid")
	}
	withPath := func(path string) string {
		derived := *parsed
		derived.Path = path
		derived.RawPath = ""
		return derived.String()
	}
	return withPath(recoveryWorkPath), withPath(recoveryResultPath), nil
}

func parseSnapshotEndpoint(endpoint string) (*url.URL, error) {
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Scheme != "https" || parsed.Host == "" ||
		parsed.Path != snapshotPath || parsed.RawQuery != "" ||
		parsed.Fragment != "" || parsed.User != nil {
		return nil, errors.New("Agent API endpoint is invalid")
	}
	return parsed, nil
}

type StatusError struct {
	StatusCode int
}

func (statusError StatusError) Error() string {
	return fmt.Sprintf(
		"HomeOps API returned status %d",
		statusError.StatusCode)
}

func (statusError StatusError) Permanent() bool {
	switch statusError.StatusCode {
	case http.StatusBadRequest,
		http.StatusNotFound,
		http.StatusMethodNotAllowed,
		http.StatusRequestEntityTooLarge,
		http.StatusUnsupportedMediaType,
		http.StatusUnprocessableEntity:
		return true
	default:
		return false
	}
}
