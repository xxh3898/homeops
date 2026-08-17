package docker

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net/http"
	"strings"
	"testing"

	"github.com/xxh3898/homeops/agent/internal/containerlog"
)

func TestContainerLogsUsesLightweightAllowlistedDockerPath(t *testing.T) {
	t.Parallel()
	fullID := "0123456789abcdef0123456789abcdef"
	calls := make(map[string]int)
	client := &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
			calls[request.URL.Path]++
			switch request.URL.Path {
			case "/version":
				return dockerResponse(http.StatusOK, `{"ApiVersion":"1.47"}`), nil
			case "/v1.47/containers/json":
				if request.URL.Query().Get("all") != "1" {
					t.Fatalf("list query = %s", request.URL.RawQuery)
				}
				return dockerResponse(http.StatusOK, `[{"Id":"`+fullID+`","Labels":{"homeops.logs":"true"}}]`), nil
			case "/v1.47/containers/" + fullID + "/json":
				return dockerResponse(http.StatusOK, `{"Config":{"Tty":false}}`), nil
			case "/v1.47/containers/" + fullID + "/logs":
				query := request.URL.Query()
				if query.Get("stdout") != "1" || query.Get("stderr") != "1" ||
					query.Get("timestamps") != "1" || query.Get("follow") != "0" ||
					query.Get("tail") != "50" || query.Has("since") || query.Has("until") {
					t.Fatalf("logs query = %s", request.URL.RawQuery)
				}
				return dockerBytesResponse(http.StatusOK,
					dockerFrame(1, "2026-08-18T01:02:03Z password=synthetic\n")), nil
			default:
				t.Fatalf("unexpected Docker path %s", request.URL.Path)
				return nil, nil
			}
		})},
		previousCPUSamples: make(map[string]cpuSample),
	}

	output, err := client.ContainerLogs(context.Background(), "0123456789ab", 50, 128)

	if err != nil {
		t.Fatalf("ContainerLogs returned an error: %v", err)
	}
	if len(output.Lines) != 1 || output.Lines[0].Message != "password=[REDACTED]" {
		t.Fatalf("output = %#v", output)
	}
	if calls["/v1.47/containers/json"] != 1 ||
		calls["/v1.47/containers/"+fullID+"/json"] != 1 ||
		calls["/v1.47/containers/"+fullID+"/logs"] != 1 {
		t.Fatalf("Docker calls = %#v", calls)
	}
	for path := range calls {
		if strings.Contains(path, "/stats") {
			t.Fatalf("logs path invoked stats: %s", path)
		}
	}
}

func TestContainerLogsFailsClosedBeforeInspectWhenPrefixIsAmbiguous(t *testing.T) {
	t.Parallel()
	inspectOrLogs := 0
	client := dockerLogClient(t, func(path string) (int, string, []byte) {
		switch path {
		case "/version":
			return http.StatusOK, `{"ApiVersion":"1.47"}`, nil
		case "/v1.47/containers/json":
			return http.StatusOK, `[
{"Id":"aaaaaaaaaaaa11111111111111111111","Labels":{"homeops.logs":"true"}},
{"Id":"aaaaaaaaaaaa22222222222222222222","Labels":{"homeops.logs":"true"}}
]`, nil
		default:
			inspectOrLogs++
			return http.StatusInternalServerError, "", nil
		}
	})

	_, err := client.ContainerLogs(context.Background(), "aaaaaaaaaaaa", 50, 128)

	assertReadErrorKind(t, err, containerlog.ReadAmbiguous)
	if inspectOrLogs != 0 {
		t.Fatalf("inspect/log calls = %d, want 0", inspectOrLogs)
	}
}

func TestContainerLogsRechecksExactOptInBeforeInspect(t *testing.T) {
	t.Parallel()
	inspectOrLogs := 0
	client := dockerLogClient(t, func(path string) (int, string, []byte) {
		switch path {
		case "/version":
			return http.StatusOK, `{"ApiVersion":"1.47"}`, nil
		case "/v1.47/containers/json":
			return http.StatusOK, `[{"Id":"0123456789abcdef","Labels":{"homeops.logs":"TRUE"}}]`, nil
		default:
			inspectOrLogs++
			return http.StatusInternalServerError, "", nil
		}
	})

	_, err := client.ContainerLogs(context.Background(), "0123456789ab", 50, 128)

	assertReadErrorKind(t, err, containerlog.ReadNotAllowed)
	if inspectOrLogs != 0 {
		t.Fatalf("inspect/log calls = %d, want 0", inspectOrLogs)
	}
}

func TestContainerLogsRejectsMalformedFullIdentifierBeforeDockerPathUse(t *testing.T) {
	t.Parallel()
	inspectOrLogs := 0
	client := dockerLogClient(t, func(path string) (int, string, []byte) {
		switch path {
		case "/version":
			return http.StatusOK, `{"ApiVersion":"1.47"}`, nil
		case "/v1.47/containers/json":
			return http.StatusOK, `[{"Id":"0123456789ab/../forbidden","Labels":{"homeops.logs":"true"}}]`, nil
		default:
			inspectOrLogs++
			return http.StatusInternalServerError, "", nil
		}
	})

	_, err := client.ContainerLogs(context.Background(), "0123456789ab", 50, 128)

	assertReadErrorKind(t, err, containerlog.ReadNotFound)
	if inspectOrLogs != 0 {
		t.Fatalf("inspect/log calls = %d, want 0", inspectOrLogs)
	}
}

func TestContainerLogsDropsRawCapCutPartialLine(t *testing.T) {
	t.Parallel()
	fullID := "0123456789abcdef"
	client := dockerLogClient(t, func(path string) (int, string, []byte) {
		switch path {
		case "/version":
			return http.StatusOK, `{"ApiVersion":"1.47"}`, nil
		case "/v1.47/containers/json":
			return http.StatusOK, `[{"Id":"` + fullID + `","Labels":{"homeops.logs":"true"}}]`, nil
		case "/v1.47/containers/" + fullID + "/json":
			return http.StatusOK, `{"Config":{"Tty":true}}`, nil
		case "/v1.47/containers/" + fullID + "/logs":
			payload := append([]byte("complete\n"), make([]byte, containerlog.MaximumRawBytes)...)
			return http.StatusOK, "", payload
		default:
			t.Fatalf("unexpected path %s", path)
			return 0, "", nil
		}
	})

	output, err := client.ContainerLogs(context.Background(), "0123456789ab", 50, 128)

	if err != nil {
		t.Fatalf("ContainerLogs returned an error: %v", err)
	}
	if !output.Truncated || len(output.Lines) != 1 || output.Lines[0].Message != "complete" {
		t.Fatalf("output = %#v", output)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (roundTrip roundTripFunc) RoundTrip(request *http.Request) (*http.Response, error) {
	return roundTrip(request)
}

func dockerResponse(status int, body string) *http.Response {
	return dockerBytesResponse(status, []byte(body))
}

func dockerBytesResponse(status int, body []byte) *http.Response {
	return &http.Response{
		StatusCode: status,
		Body:       io.NopCloser(strings.NewReader(string(body))),
		Header:     make(http.Header),
	}
}

func dockerLogClient(
	t *testing.T,
	response func(string) (int, string, []byte),
) *Client {
	t.Helper()
	return &Client{
		httpClient: &http.Client{Transport: roundTripFunc(func(request *http.Request) (*http.Response, error) {
			status, text, raw := response(request.URL.Path)
			if raw != nil {
				return dockerBytesResponse(status, raw), nil
			}
			return dockerResponse(status, text), nil
		})},
		previousCPUSamples: make(map[string]cpuSample),
	}
}

func assertReadErrorKind(
	t *testing.T,
	err error,
	want containerlog.ReadErrorKind,
) {
	t.Helper()
	var readError containerlog.ReadError
	if !errors.As(err, &readError) || readError.Kind != want {
		t.Fatalf("error = %#v, want kind %s", err, want)
	}
	if strings.Contains(err.Error(), string(want)) {
		t.Fatalf("error leaked internal kind: %q", err)
	}
}

func dockerFrame(stream byte, payload string) []byte {
	header := make([]byte, 8)
	header[0] = stream
	binary.BigEndian.PutUint32(header[4:], uint32(len(payload)))
	return append(header, payload...)
}
