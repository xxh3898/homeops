package docker

import (
	"context"
	"io"
	"net/http"
	"net/http/httptrace"
	"net/url"
	"strings"
	"sync/atomic"
	"time"

	"github.com/xxh3898/homeops/agent/internal/containercontrol"
)

const maximumControlErrorResponse = 4 * 1024

var protectedComposeServices = map[string]struct{}{
	"db":         {},
	"database":   {},
	"mysql":      {},
	"postgres":   {},
	"postgresql": {},
	"mariadb":    {},
	"redis":      {},
}

func (client *Client) ControlContainer(
	ctx context.Context,
	shortIdentifier string,
	expectedProject string,
	operation containercontrol.Operation,
	maximumContainers int,
	expiresAt time.Time,
) containercontrol.Outcome {
	if !containercontrol.ValidContainerID(shortIdentifier) ||
		!containercontrol.ValidProject(expectedProject) ||
		expectedProject == "homeops" || !operation.Valid() {
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	if !client.controlWorkCurrent(expiresAt) {
		return expiredControl()
	}
	remaining := expiresAt.Sub(client.currentTime())
	workContext, cancel := context.WithTimeout(ctx, remaining)
	defer cancel()

	version, err := client.apiVersion(workContext)
	if err != nil {
		if !client.controlWorkCurrent(expiresAt) {
			return expiredControl()
		}
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	if !client.controlWorkCurrent(expiresAt) {
		return expiredControl()
	}

	var listed []listedContainer
	if err := client.getJSON(
		workContext,
		"/v"+version+"/containers/json?all=1",
		maximumListResponse,
		&listed); err != nil {
		if !client.controlWorkCurrent(expiresAt) {
			return expiredControl()
		}
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	if len(listed) > maximumContainers {
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	matches := make([]listedContainer, 0, 2)
	for _, item := range listed {
		if containercontrol.ValidFullContainerID(item.ID) &&
			strings.HasPrefix(item.ID, shortIdentifier) {
			matches = append(matches, item)
			if len(matches) == 2 {
				break
			}
		}
	}
	if len(matches) == 0 {
		return deniedControl(containercontrol.ReasonContainerNotFound)
	}
	if len(matches) > 1 {
		return deniedControl(containercontrol.ReasonAmbiguousIdentifier)
	}
	if !client.controlWorkCurrent(expiresAt) {
		return expiredControl()
	}

	selected := matches[0]
	var inspected controlInspect
	inspectPath := "/v" + version + "/containers/" +
		url.PathEscape(selected.ID) + "/json"
	if err := client.getJSON(
		workContext,
		inspectPath,
		maximumInspectResponse,
		&inspected); err != nil {
		if hasDockerStatus(err, http.StatusNotFound) {
			return deniedControl(containercontrol.ReasonContainerNotFound)
		}
		if !client.controlWorkCurrent(expiresAt) {
			return expiredControl()
		}
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	if outcome := validateControlInspect(inspected, expectedProject); outcome != nil {
		return *outcome
	}
	if !client.controlWorkCurrent(expiresAt) {
		return expiredControl()
	}

	return client.executeControlOperation(
		workContext,
		version,
		selected.ID,
		operation,
		expiresAt)
}

type controlInspect struct {
	Config *struct {
		Labels map[string]string `json:"Labels"`
	} `json:"Config"`
	Mounts *[]controlMount `json:"Mounts"`
}

type controlMount struct {
	Type string `json:"Type"`
	RW   *bool  `json:"RW"`
}

func validateControlInspect(
	inspected controlInspect,
	expectedProject string,
) *containercontrol.Outcome {
	if inspected.Config == nil || inspected.Config.Labels == nil {
		outcome := deniedControl(containercontrol.ReasonNotManaged)
		return &outcome
	}
	labels := inspected.Config.Labels
	if labels["homeops.managed"] != "true" {
		outcome := deniedControl(containercontrol.ReasonNotManaged)
		return &outcome
	}
	liveProject := labels["com.docker.compose.project"]
	if liveProject == "homeops" {
		outcome := deniedControl(containercontrol.ReasonProtectedProject)
		return &outcome
	}
	if liveProject != expectedProject {
		outcome := deniedControl(containercontrol.ReasonProjectMismatch)
		return &outcome
	}
	service := labels["com.docker.compose.service"]
	if service == "" {
		outcome := deniedControl(containercontrol.ReasonComposeServiceUnavailable)
		return &outcome
	}
	if _, protected := protectedComposeServices[service]; protected {
		outcome := deniedControl(containercontrol.ReasonProtectedService)
		return &outcome
	}
	if inspected.Mounts == nil {
		outcome := deniedControl(containercontrol.ReasonMountProtectionUnavailable)
		return &outcome
	}
	for _, mount := range *inspected.Mounts {
		if mount.RW == nil {
			outcome := deniedControl(containercontrol.ReasonMountProtectionUnavailable)
			return &outcome
		}
		switch mount.Type {
		case "bind", "volume":
			if *mount.RW {
				outcome := deniedControl(containercontrol.ReasonWritableMount)
				return &outcome
			}
		case "tmpfs":
			// tmpfs does not retain host or named-volume state.
		default:
			outcome := deniedControl(containercontrol.ReasonMountProtectionUnavailable)
			return &outcome
		}
	}
	return nil
}

func (client *Client) executeControlOperation(
	ctx context.Context,
	version string,
	fullIdentifier string,
	operation containercontrol.Operation,
	expiresAt time.Time,
) containercontrol.Outcome {
	path := "/v" + version + "/containers/" + url.PathEscape(fullIdentifier)
	switch operation {
	case containercontrol.OperationStart:
		path += "/start"
	case containercontrol.OperationStop:
		path += "/stop?t=10"
	case containercontrol.OperationRestart:
		path += "/restart?t=10"
	default:
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodPost,
		"http://docker"+path,
		nil)
	if err != nil {
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	var wroteRequest atomic.Bool
	trace := &httptrace.ClientTrace{
		WroteRequest: func(httptrace.WroteRequestInfo) {
			wroteRequest.Store(true)
		},
	}
	request = request.WithContext(httptrace.WithClientTrace(request.Context(), trace))
	operationClient := *client.httpClient
	operationClient.CheckRedirect = func(
		*http.Request,
		[]*http.Request,
	) error {
		return http.ErrUseLastResponse
	}
	response, err := operationClient.Do(request)
	if err != nil {
		if wroteRequest.Load() {
			return containercontrol.Outcome{
				Status:     containercontrol.StatusOutcomeUnknown,
				ReasonCode: containercontrol.ReasonDockerOutcomeUnknown,
			}
		}
		if !client.controlWorkCurrent(expiresAt) {
			return expiredControl()
		}
		return failedControl(containercontrol.ReasonDockerUnavailable)
	}
	defer response.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(
		response.Body,
		maximumControlErrorResponse))

	if response.StatusCode == http.StatusNoContent {
		return containercontrol.Outcome{
			Status:     containercontrol.StatusApplied,
			ReasonCode: containercontrol.ReasonApplied,
		}
	}
	if response.StatusCode == http.StatusNotModified {
		switch operation {
		case containercontrol.OperationStart:
			return containercontrol.Outcome{
				Status:     containercontrol.StatusNoop,
				ReasonCode: containercontrol.ReasonAlreadyRunning,
			}
		case containercontrol.OperationStop:
			return containercontrol.Outcome{
				Status:     containercontrol.StatusNoop,
				ReasonCode: containercontrol.ReasonAlreadyStopped,
			}
		}
	}
	return failedControl(containercontrol.ReasonDockerRejected)
}

func (client *Client) controlWorkCurrent(expiresAt time.Time) bool {
	return containercontrol.ValidateExpiry(
		expiresAt,
		client.currentTime()) == nil
}

func deniedControl(reason containercontrol.ReasonCode) containercontrol.Outcome {
	return containercontrol.Outcome{
		Status:     containercontrol.StatusDenied,
		ReasonCode: reason,
	}
}

func failedControl(reason containercontrol.ReasonCode) containercontrol.Outcome {
	return containercontrol.Outcome{
		Status:     containercontrol.StatusFailed,
		ReasonCode: reason,
	}
}

func expiredControl() containercontrol.Outcome {
	return containercontrol.Outcome{
		Status:     containercontrol.StatusExpired,
		ReasonCode: containercontrol.ReasonWorkExpired,
	}
}
