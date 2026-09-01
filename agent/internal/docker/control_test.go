package docker

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptrace"
	"strings"
	"testing"
	"time"

	"github.com/xxh3898/homeops/agent/internal/containercontrol"
)

var dockerControlTestNow = time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)

func TestControlContainerUsesOneLiveListInspectAndFixedMutation(t *testing.T) {
	t.Parallel()
	operations := []struct {
		operation containercontrol.Operation
		path      string
		query     string
	}{
		{containercontrol.OperationStart, "/start", ""},
		{containercontrol.OperationStop, "/stop", "t=10"},
		{containercontrol.OperationRestart, "/restart", "t=10"},
	}
	for _, test := range operations {
		test := test
		t.Run(string(test.operation), func(t *testing.T) {
			t.Parallel()
			fullID := "0123456789abcdef0123456789abcdef"
			calls := make(map[string]int)
			client := controlTestClient(t, func(request *http.Request) (*http.Response, error) {
				calls[request.URL.Path]++
				switch request.URL.Path {
				case "/version":
					return dockerResponse(http.StatusOK, `{"ApiVersion":"1.47"}`), nil
				case "/v1.47/containers/json":
					if request.URL.RawQuery != "all=1" {
						t.Fatalf("list query = %q", request.URL.RawQuery)
					}
					return dockerResponse(http.StatusOK, `[{"Id":"`+fullID+`"}]`), nil
				case "/v1.47/containers/" + fullID + "/json":
					return dockerResponse(http.StatusOK, safeControlInspect()), nil
				case "/v1.47/containers/" + fullID + test.path:
					if request.Method != http.MethodPost || request.URL.RawQuery != test.query ||
						request.Body != nil {
						t.Fatalf("mutation = %s %s?%s body=%v",
							request.Method, request.URL.Path, request.URL.RawQuery, request.Body)
					}
					return dockerResponse(http.StatusNoContent, "ignored"), nil
				default:
					t.Fatalf("unexpected Docker path %s", request.URL.Path)
					return nil, nil
				}
			})

			outcome := client.ControlContainer(
				context.Background(),
				"0123456789ab",
				"example",
				test.operation,
				128,
				dockerControlTestNow.Add(15*time.Second))

			if outcome.Status != containercontrol.StatusApplied ||
				outcome.ReasonCode != containercontrol.ReasonApplied {
				t.Fatalf("outcome = %#v", outcome)
			}
			if calls["/v1.47/containers/json"] != 1 ||
				calls["/v1.47/containers/"+fullID+"/json"] != 1 ||
				calls["/v1.47/containers/"+fullID+test.path] != 1 {
				t.Fatalf("Docker calls = %#v", calls)
			}
			for path := range calls {
				if strings.Contains(path, "/stats") || strings.Contains(path, "/logs") {
					t.Fatalf("control invoked unrelated Docker path %s", path)
				}
			}
		})
	}
}

func TestControlContainerFailsClosedForZeroOrAmbiguousFullID(t *testing.T) {
	t.Parallel()
	for name, list := range map[string]string{
		"absent": `[{"Id":"ffffffffffffffff"}]`,
		"ambiguous": `[
{"Id":"aaaaaaaaaaaa11111111111111111111"},
{"Id":"aaaaaaaaaaaa22222222222222222222"}
]`,
	} {
		name, list := name, list
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			inspectOrMutation := 0
			client := controlTestClient(t, func(request *http.Request) (*http.Response, error) {
				switch request.URL.Path {
				case "/version":
					return dockerResponse(http.StatusOK, `{"ApiVersion":"1.47"}`), nil
				case "/v1.47/containers/json":
					return dockerResponse(http.StatusOK, list), nil
				default:
					inspectOrMutation++
					return dockerResponse(http.StatusInternalServerError, ""), nil
				}
			})
			outcome := client.ControlContainer(
				context.Background(),
				map[bool]string{true: "aaaaaaaaaaaa", false: "0123456789ab"}[name == "ambiguous"],
				"example",
				containercontrol.OperationStart,
				128,
				dockerControlTestNow.Add(15*time.Second))

			want := containercontrol.ReasonContainerNotFound
			if name == "ambiguous" {
				want = containercontrol.ReasonAmbiguousIdentifier
			}
			if outcome.Status != containercontrol.StatusDenied || outcome.ReasonCode != want {
				t.Fatalf("outcome = %#v, want %s", outcome, want)
			}
			if inspectOrMutation != 0 {
				t.Fatalf("inspect/mutation calls = %d, want 0", inspectOrMutation)
			}
		})
	}
}

func TestControlContainerRevalidatesExactLabelsServiceAndMounts(t *testing.T) {
	t.Parallel()
	tests := map[string]struct {
		inspect string
		reason  containercontrol.ReasonCode
	}{
		"missing managed": {
			inspect: controlInspectFixture(`"com.docker.compose.project":"example","com.docker.compose.service":"api"`, `[]`),
			reason:  containercontrol.ReasonNotManaged,
		},
		"non exact managed": {
			inspect: controlInspectFixture(`"homeops.managed":"TRUE","com.docker.compose.project":"example","com.docker.compose.service":"api"`, `[]`),
			reason:  containercontrol.ReasonNotManaged,
		},
		"project mismatch": {
			inspect: controlInspectFixture(`"homeops.managed":"true","com.docker.compose.project":"other","com.docker.compose.service":"api"`, `[]`),
			reason:  containercontrol.ReasonProjectMismatch,
		},
		"homeops project": {
			inspect: controlInspectFixture(`"homeops.managed":"true","com.docker.compose.project":"homeops","com.docker.compose.service":"api"`, `[]`),
			reason:  containercontrol.ReasonProtectedProject,
		},
		"missing service": {
			inspect: controlInspectFixture(`"homeops.managed":"true","com.docker.compose.project":"example"`, `[]`),
			reason:  containercontrol.ReasonComposeServiceUnavailable,
		},
		"writable bind": {
			inspect: controlInspectFixture(safeControlLabels(), `[{"Type":"bind","RW":true,"Source":"must-not-leak"}]`),
			reason:  containercontrol.ReasonWritableMount,
		},
		"writable volume": {
			inspect: controlInspectFixture(safeControlLabels(), `[{"Type":"volume","RW":true,"Source":"must-not-leak"}]`),
			reason:  containercontrol.ReasonWritableMount,
		},
		"missing mounts": {
			inspect: `{"Config":{"Labels":{` + safeControlLabels() + `}}}`,
			reason:  containercontrol.ReasonMountProtectionUnavailable,
		},
		"missing rw": {
			inspect: controlInspectFixture(safeControlLabels(), `[{"Type":"bind"}]`),
			reason:  containercontrol.ReasonMountProtectionUnavailable,
		},
		"unknown mount": {
			inspect: controlInspectFixture(safeControlLabels(), `[{"Type":"mystery","RW":false}]`),
			reason:  containercontrol.ReasonMountProtectionUnavailable,
		},
	}
	for name, test := range tests {
		name, test := name, test
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			mutationCalls := 0
			client := controlClientWithInspect(t, test.inspect, func(*http.Request) (*http.Response, error) {
				mutationCalls++
				return dockerResponse(http.StatusNoContent, ""), nil
			})

			outcome := client.ControlContainer(
				context.Background(),
				"0123456789ab",
				"example",
				containercontrol.OperationStart,
				128,
				dockerControlTestNow.Add(15*time.Second))

			if outcome.Status != containercontrol.StatusDenied || outcome.ReasonCode != test.reason {
				t.Fatalf("outcome = %#v, want %s", outcome, test.reason)
			}
			if mutationCalls != 0 {
				t.Fatalf("mutation calls = %d, want 0", mutationCalls)
			}
		})
	}
}

func TestControlContainerKeepsRhaomiBackendWritableMountDenied(t *testing.T) {
	t.Parallel()
	inspect := controlInspectFixture(
		`"homeops.managed":"true","com.docker.compose.project":"rhaomi","com.docker.compose.service":"backend"`,
		`[{"Type":"bind","RW":true,"Source":"must-not-leak"}]`)
	client := controlClientWithInspect(t, inspect, func(*http.Request) (*http.Response, error) {
		t.Fatal("Rhaomi backend writable mount reached generic Docker mutation")
		return nil, nil
	})

	outcome := client.ControlContainer(
		context.Background(),
		"0123456789ab",
		"rhaomi",
		containercontrol.OperationRestart,
		128,
		dockerControlTestNow.Add(15*time.Second))

	if outcome.Status != containercontrol.StatusDenied ||
		outcome.ReasonCode != containercontrol.ReasonWritableMount {
		t.Fatalf("outcome = %#v", outcome)
	}
}

func TestControlContainerHardDeniesExactProtectedServices(t *testing.T) {
	t.Parallel()
	services := []string{"db", "database", "mysql", "postgres", "postgresql", "mariadb", "redis"}
	for _, service := range services {
		service := service
		t.Run(service, func(t *testing.T) {
			t.Parallel()
			inspect := controlInspectFixture(
				`"homeops.managed":"true","com.docker.compose.project":"example","com.docker.compose.service":"`+service+`"`,
				`[]`)
			client := controlClientWithInspect(t, inspect, func(*http.Request) (*http.Response, error) {
				t.Fatal("protected service reached Docker mutation")
				return nil, nil
			})

			outcome := client.ControlContainer(
				context.Background(), "0123456789ab", "example",
				containercontrol.OperationStop, 128,
				dockerControlTestNow.Add(15*time.Second))

			if outcome.Status != containercontrol.StatusDenied ||
				outcome.ReasonCode != containercontrol.ReasonProtectedService {
				t.Fatalf("outcome = %#v", outcome)
			}
		})
	}
}

func TestControlContainerDoesNotTreatProtectedNamePrefixesAsProtected(t *testing.T) {
	t.Parallel()
	inspect := controlInspectFixture(
		`"homeops.managed":"true","com.docker.compose.project":"example","com.docker.compose.service":"redis-worker"`,
		`[]`)
	client := controlClientWithInspect(t, inspect, func(*http.Request) (*http.Response, error) {
		return dockerResponse(http.StatusNoContent, ""), nil
	})

	outcome := client.ControlContainer(
		context.Background(), "0123456789ab", "example",
		containercontrol.OperationStart, 128,
		dockerControlTestNow.Add(15*time.Second))

	if outcome.Status != containercontrol.StatusApplied {
		t.Fatalf("outcome = %#v", outcome)
	}
}

func TestControlContainerAllowsReadonlyPersistentAndTmpfsMounts(t *testing.T) {
	t.Parallel()
	inspect := controlInspectFixture(safeControlLabels(), `[
{"Type":"bind","RW":false},
{"Type":"volume","RW":false},
{"Type":"tmpfs","RW":true}
]`)
	client := controlClientWithInspect(t, inspect, func(*http.Request) (*http.Response, error) {
		return dockerResponse(http.StatusNoContent, ""), nil
	})

	outcome := client.ControlContainer(
		context.Background(), "0123456789ab", "example",
		containercontrol.OperationRestart, 128,
		dockerControlTestNow.Add(15*time.Second))

	if outcome.Status != containercontrol.StatusApplied {
		t.Fatalf("outcome = %#v", outcome)
	}
}

func TestControlContainerMapsDockerNoopRejectionAndRedirectWithoutFollowing(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name      string
		operation containercontrol.Operation
		status    int
		headers   http.Header
		want      containercontrol.Outcome
	}{
		{"start noop", containercontrol.OperationStart, http.StatusNotModified, nil,
			containercontrol.Outcome{Status: containercontrol.StatusNoop, ReasonCode: containercontrol.ReasonAlreadyRunning}},
		{"stop noop", containercontrol.OperationStop, http.StatusNotModified, nil,
			containercontrol.Outcome{Status: containercontrol.StatusNoop, ReasonCode: containercontrol.ReasonAlreadyStopped}},
		{"restart unexpected noop", containercontrol.OperationRestart, http.StatusNotModified, nil,
			containercontrol.Outcome{Status: containercontrol.StatusFailed, ReasonCode: containercontrol.ReasonDockerRejected}},
		{"terminal rejection", containercontrol.OperationStart, http.StatusConflict, nil,
			containercontrol.Outcome{Status: containercontrol.StatusFailed, ReasonCode: containercontrol.ReasonDockerRejected}},
		{"redirect", containercontrol.OperationStart, http.StatusFound,
			http.Header{"Location": []string{"http://docker/forbidden"}},
			containercontrol.Outcome{Status: containercontrol.StatusFailed, ReasonCode: containercontrol.ReasonDockerRejected}},
	}
	for _, test := range tests {
		test := test
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			mutationCalls := 0
			client := controlClientWithInspect(t, safeControlInspect(), func(*http.Request) (*http.Response, error) {
				mutationCalls++
				response := dockerResponse(test.status, "raw-private-body")
				response.Header = test.headers
				return response, nil
			})

			outcome := client.ControlContainer(
				context.Background(), "0123456789ab", "example",
				test.operation, 128,
				dockerControlTestNow.Add(15*time.Second))

			if outcome != test.want {
				t.Fatalf("outcome = %#v, want %#v", outcome, test.want)
			}
			if mutationCalls != 1 {
				t.Fatalf("mutation calls = %d, want 1", mutationCalls)
			}
		})
	}
}

func TestControlContainerDistinguishesPreSendFailureFromAmbiguousPostSendFailure(t *testing.T) {
	t.Parallel()
	for name, wrote := range map[string]bool{"pre-send": false, "post-send": true} {
		name, wrote := name, wrote
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			client := controlClientWithInspect(t, safeControlInspect(), func(request *http.Request) (*http.Response, error) {
				if wrote {
					trace := httptrace.ContextClientTrace(request.Context())
					trace.WroteRequest(httptrace.WroteRequestInfo{})
				}
				return nil, errors.New("synthetic transport failure")
			})

			outcome := client.ControlContainer(
				context.Background(), "0123456789ab", "example",
				containercontrol.OperationStart, 128,
				dockerControlTestNow.Add(15*time.Second))

			want := containercontrol.Outcome{
				Status:     containercontrol.StatusFailed,
				ReasonCode: containercontrol.ReasonDockerUnavailable,
			}
			if wrote {
				want = containercontrol.Outcome{
					Status:     containercontrol.StatusOutcomeUnknown,
					ReasonCode: containercontrol.ReasonDockerOutcomeUnknown,
				}
			}
			if outcome != want {
				t.Fatalf("outcome = %#v, want %#v", outcome, want)
			}
		})
	}
}

func TestControlContainerMapsPreSendDeadlineExpiryToExpired(t *testing.T) {
	t.Parallel()
	clockReads := 0
	client := controlClientWithInspect(t, safeControlInspect(), func(*http.Request) (*http.Response, error) {
		return nil, context.DeadlineExceeded
	})
	client.now = func() time.Time {
		clockReads++
		if clockReads >= 5 {
			return dockerControlTestNow.Add(15 * time.Second)
		}
		return dockerControlTestNow
	}

	outcome := client.ControlContainer(
		context.Background(), "0123456789ab", "example",
		containercontrol.OperationStart, 128,
		dockerControlTestNow.Add(15*time.Second))

	if outcome.Status != containercontrol.StatusExpired ||
		outcome.ReasonCode != containercontrol.ReasonWorkExpired {
		t.Fatalf("outcome = %#v", outcome)
	}
}

func TestControlContainerBoundsAndClosesRawDockerErrorBody(t *testing.T) {
	t.Parallel()
	body := &countingReadCloser{reader: strings.NewReader(
		strings.Repeat("private-error-body", maximumControlErrorResponse))}
	client := controlClientWithInspect(t, safeControlInspect(), func(*http.Request) (*http.Response, error) {
		return &http.Response{
			StatusCode: http.StatusInternalServerError,
			Body:       body,
			Header:     make(http.Header),
		}, nil
	})

	outcome := client.ControlContainer(
		context.Background(), "0123456789ab", "example",
		containercontrol.OperationStart, 128,
		dockerControlTestNow.Add(15*time.Second))

	if outcome.Status != containercontrol.StatusFailed ||
		outcome.ReasonCode != containercontrol.ReasonDockerRejected {
		t.Fatalf("outcome = %#v", outcome)
	}
	if body.read > maximumControlErrorResponse || !body.closed {
		t.Fatalf("body read/closed = %d/%v", body.read, body.closed)
	}
}

func TestControlContainerRejectsExpiredWorkBeforeDockerAndRechecksBeforeMutation(t *testing.T) {
	t.Parallel()
	preCalls := 0
	pre := controlTestClient(t, func(*http.Request) (*http.Response, error) {
		preCalls++
		return dockerResponse(http.StatusInternalServerError, ""), nil
	})
	preOutcome := pre.ControlContainer(
		context.Background(), "0123456789ab", "example",
		containercontrol.OperationStart, 128, dockerControlTestNow)
	if preOutcome.Status != containercontrol.StatusExpired || preCalls != 0 {
		t.Fatalf("pre outcome/calls = %#v/%d", preOutcome, preCalls)
	}

	clockReads := 0
	mutationCalls := 0
	client := controlClientWithInspect(t, safeControlInspect(), func(*http.Request) (*http.Response, error) {
		mutationCalls++
		return dockerResponse(http.StatusNoContent, ""), nil
	})
	client.now = func() time.Time {
		clockReads++
		if clockReads >= 4 {
			return dockerControlTestNow.Add(15 * time.Second)
		}
		return dockerControlTestNow
	}
	outcome := client.ControlContainer(
		context.Background(), "0123456789ab", "example",
		containercontrol.OperationStart, 128,
		dockerControlTestNow.Add(15*time.Second))
	if outcome.Status != containercontrol.StatusExpired || mutationCalls != 0 {
		t.Fatalf("outcome/mutation calls = %#v/%d", outcome, mutationCalls)
	}
}

func controlTestClient(
	t *testing.T,
	roundTrip func(*http.Request) (*http.Response, error),
) *Client {
	t.Helper()
	return &Client{
		httpClient:         &http.Client{Transport: roundTripFunc(roundTrip)},
		previousCPUSamples: make(map[string]cpuSample),
		now:                func() time.Time { return dockerControlTestNow },
	}
}

func controlClientWithInspect(
	t *testing.T,
	inspect string,
	mutate func(*http.Request) (*http.Response, error),
) *Client {
	t.Helper()
	fullID := "0123456789abcdef0123456789abcdef"
	return controlTestClient(t, func(request *http.Request) (*http.Response, error) {
		switch request.URL.Path {
		case "/version":
			return dockerResponse(http.StatusOK, `{"ApiVersion":"1.47"}`), nil
		case "/v1.47/containers/json":
			return dockerResponse(http.StatusOK, `[{"Id":"`+fullID+`"}]`), nil
		case "/v1.47/containers/" + fullID + "/json":
			return dockerResponse(http.StatusOK, inspect), nil
		default:
			return mutate(request)
		}
	})
}

func safeControlInspect() string {
	return controlInspectFixture(safeControlLabels(), `[]`)
}

func safeControlLabels() string {
	return `"homeops.managed":"true","com.docker.compose.project":"example","com.docker.compose.service":"api"`
}

func controlInspectFixture(labels string, mounts string) string {
	return `{"Config":{"Labels":{` + labels + `}},"Mounts":` + mounts + `}`
}

type countingReadCloser struct {
	reader io.Reader
	read   int
	closed bool
}

func (reader *countingReadCloser) Read(target []byte) (int, error) {
	read, err := reader.reader.Read(target)
	reader.read += read
	return read, err
}

func (reader *countingReadCloser) Close() error {
	reader.closed = true
	return nil
}
