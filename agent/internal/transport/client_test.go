package transport

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/xxh3898/homeops/agent/internal/containerlog"
)

func TestStatusErrorClassifiesValidationRejectionAsPermanent(t *testing.T) {
	t.Parallel()
	if !((StatusError{StatusCode: http.StatusUnprocessableEntity}).Permanent()) {
		t.Fatal("422 rejection must be permanent")
	}
}

func TestStatusErrorKeepsAuthenticationFailureRetryable(t *testing.T) {
	t.Parallel()
	if (StatusError{StatusCode: http.StatusUnauthorized}).Permanent() {
		t.Fatal("401 rejection must remain retryable after credential repair")
	}
}

func TestDeriveEndpointsUsesFixedPathsOnValidatedOrigin(t *testing.T) {
	t.Parallel()
	snapshot, work, result, err := deriveEndpoints(
		"https://127.0.0.1:13443/api/v1/internal/agent/snapshots")
	if err != nil {
		t.Fatalf("deriveEndpoints returned an error: %v", err)
	}
	if snapshot != "https://127.0.0.1:13443"+snapshotPath ||
		work != "https://127.0.0.1:13443"+logWorkPath ||
		result != "https://127.0.0.1:13443"+logResultPath {
		t.Fatalf("derived endpoints = %q, %q, %q", snapshot, work, result)
	}
}

func TestDeriveEndpointsRejectsAlternateConfiguredPath(t *testing.T) {
	t.Parallel()
	if _, _, _, err := deriveEndpoints(
		"https://127.0.0.1:13443/api/v1/internal/agent/log-results"); err == nil {
		t.Fatal("deriveEndpoints accepted an alternate configured path")
	}
}

func TestNextContainerLogWorkAcceptsBoundedWork(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	server := httptest.NewServer(http.HandlerFunc(func(
		response http.ResponseWriter,
		request *http.Request,
	) {
		if request.Method != http.MethodGet || request.URL.Path != logWorkPath {
			t.Fatalf("request = %s %s", request.Method, request.URL.Path)
		}
		response.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(response,
			`{"requestId":"10000000-0000-4000-8000-000000000001","containerId":"0123456789ab","tail":50,"expiresAt":"2026-08-18T01:02:09Z"}`)
	}))
	defer server.Close()
	client := &Client{
		logWorkEndpoint: server.URL + logWorkPath,
		httpClient:      server.Client(),
		now:             func() time.Time { return now },
	}

	work, err := client.NextContainerLogWork(context.Background())

	if err != nil || work == nil || work.Tail != 50 || work.ContainerID != "0123456789ab" {
		t.Fatalf("work/error = %#v/%v", work, err)
	}
}

func TestNextContainerLogWorkTreatsOldAPI404AsStatusError(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.NotFoundHandler())
	defer server.Close()
	client := &Client{logWorkEndpoint: server.URL + logWorkPath, httpClient: server.Client()}

	_, err := client.NextContainerLogWork(context.Background())

	statusError, ok := err.(StatusError)
	if !ok || statusError.StatusCode != http.StatusNotFound {
		t.Fatalf("error = %#v, want 404 StatusError", err)
	}
}

func TestNextContainerLogWorkRejectsUnknownCommandField(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(
		response http.ResponseWriter,
		_ *http.Request,
	) {
		response.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(response,
			`{"requestId":"10000000-0000-4000-8000-000000000001","containerId":"0123456789ab","tail":50,"expiresAt":"2099-08-18T01:02:09Z","command":"forbidden"}`)
	}))
	defer server.Close()
	client := &Client{logWorkEndpoint: server.URL + logWorkPath, httpClient: server.Client()}

	work, err := client.NextContainerLogWork(context.Background())

	if err == nil || work != nil {
		t.Fatalf("work/error = %#v/%v, want strict rejection", work, err)
	}
}

func TestNextContainerLogWorkRejectsTailOutsideAllowlist(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(
		response http.ResponseWriter,
		_ *http.Request,
	) {
		response.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(response,
			`{"requestId":"10000000-0000-4000-8000-000000000001","containerId":"0123456789ab","tail":201,"expiresAt":"2099-08-18T01:02:09Z"}`)
	}))
	defer server.Close()
	client := &Client{logWorkEndpoint: server.URL + logWorkPath, httpClient: server.Client()}

	work, err := client.NextContainerLogWork(context.Background())

	if err == nil || work != nil {
		t.Fatalf("work/error = %#v/%v, want bounded rejection", work, err)
	}
}

func TestNextContainerLogWorkRejectsMissingMalformedExpiredAndFarExpiry(
	t *testing.T,
) {
	t.Parallel()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	cases := map[string]string{
		"missing":    `{"requestId":"10000000-0000-4000-8000-000000000001","containerId":"0123456789ab","tail":50}`,
		"malformed":  `{"requestId":"10000000-0000-4000-8000-000000000001","containerId":"0123456789ab","tail":50,"expiresAt":"not-a-time"}`,
		"expired":    `{"requestId":"10000000-0000-4000-8000-000000000001","containerId":"0123456789ab","tail":50,"expiresAt":"2026-08-18T01:02:03Z"}`,
		"far future": `{"requestId":"10000000-0000-4000-8000-000000000001","containerId":"0123456789ab","tail":50,"expiresAt":"2026-08-18T01:02:14Z"}`,
	}
	for name, payload := range cases {
		name, payload := name, payload
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			server := httptest.NewServer(http.HandlerFunc(func(
				response http.ResponseWriter,
				_ *http.Request,
			) {
				response.Header().Set("Content-Type", "application/json")
				_, _ = io.WriteString(response, payload)
			}))
			defer server.Close()
			client := &Client{
				logWorkEndpoint: server.URL + logWorkPath,
				httpClient:      server.Client(),
				now:             func() time.Time { return now },
			}

			work, err := client.NextContainerLogWork(context.Background())

			if err == nil || work != nil {
				t.Fatalf("work/error = %#v/%v, want strict expiry rejection", work, err)
			}
		})
	}
}

func TestSendContainerLogResultPostsOnlyBoundedDTO(t *testing.T) {
	t.Parallel()
	server := httptest.NewServer(http.HandlerFunc(func(
		response http.ResponseWriter,
		request *http.Request,
	) {
		if request.Method != http.MethodPost || request.URL.Path != logResultPath {
			t.Fatalf("request = %s %s", request.Method, request.URL.Path)
		}
		body, err := io.ReadAll(request.Body)
		if err != nil {
			t.Fatalf("read body: %v", err)
		}
		if strings.Contains(string(body), "fullContainerId") ||
			!strings.Contains(string(body), `"message":"safe"`) ||
			!strings.Contains(string(body), `"collectedAt":"2026-08-18T01:02:04Z"`) ||
			!strings.Contains(string(body), `"redactionApplied":true`) {
			t.Fatalf("result body = %s", body)
		}
		response.WriteHeader(http.StatusNoContent)
	}))
	defer server.Close()
	client := &Client{logResultEndpoint: server.URL + logResultPath, httpClient: server.Client()}

	err := client.SendContainerLogResult(context.Background(), containerlog.Result{
		RequestID:   "10000000-0000-4000-8000-000000000001",
		Status:      containerlog.StatusSuccess,
		CollectedAt: time.Date(2026, 8, 18, 1, 2, 4, 0, time.UTC),
		Lines: []containerlog.Line{{
			Stream:  containerlog.StreamStdout,
			Message: "safe",
		}},
		RedactionApplied: true,
	})
	if err != nil {
		t.Fatalf("SendContainerLogResult returned an error: %v", err)
	}
}
