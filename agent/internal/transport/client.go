package transport

import (
	"bytes"
	"context"
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"time"
)

type Client struct {
	endpoint   string
	httpClient *http.Client
}

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
		endpoint: endpoint,
		httpClient: &http.Client{
			Transport: transport,
			Timeout:   10 * time.Second,
		},
	}, nil
}

func (client *Client) Send(ctx context.Context, payload []byte) error {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		client.endpoint,
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
