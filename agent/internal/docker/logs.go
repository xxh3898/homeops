package docker

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/xxh3898/homeops/agent/internal/containerlog"
)

func (client *Client) ContainerLogs(
	ctx context.Context,
	shortIdentifier string,
	tail int,
	maximumContainers int,
	expiresAt time.Time,
) (containerlog.Output, error) {
	if !containerlog.ValidContainerID(shortIdentifier) ||
		!containerlog.AllowedTail(tail) {
		return containerlog.Output{}, unavailableLogError()
	}
	now := client.currentTime()
	if err := containerlog.ValidateExpiry(expiresAt, now); err != nil {
		return containerlog.Output{}, unavailableLogError()
	}
	remaining := expiresAt.Sub(now)
	timeout := min(remaining, containerlog.DockerTimeout)
	workContext, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	version, err := client.apiVersion(workContext)
	if err != nil {
		return containerlog.Output{}, unavailableLogError()
	}
	if err := client.ensureLogWorkCurrent(expiresAt); err != nil {
		return containerlog.Output{}, err
	}
	var listed []listedContainer
	if err := client.getJSON(
		workContext,
		"/v"+version+"/containers/json?all=1",
		maximumListResponse,
		&listed); err != nil {
		return containerlog.Output{}, unavailableLogError()
	}
	if len(listed) > maximumContainers {
		return containerlog.Output{}, unavailableLogError()
	}
	matches := make([]listedContainer, 0, 2)
	for _, item := range listed {
		if containerlog.ValidFullContainerID(item.ID) &&
			strings.HasPrefix(item.ID, shortIdentifier) {
			matches = append(matches, item)
			if len(matches) == 2 {
				break
			}
		}
	}
	if len(matches) == 0 {
		return containerlog.Output{}, containerlog.ReadError{
			Kind: containerlog.ReadNotFound,
		}
	}
	if len(matches) > 1 {
		return containerlog.Output{}, containerlog.ReadError{
			Kind: containerlog.ReadAmbiguous,
		}
	}
	selected := matches[0]
	if selected.Labels["homeops.logs"] != "true" {
		return containerlog.Output{}, containerlog.ReadError{
			Kind: containerlog.ReadNotAllowed,
		}
	}
	if err := client.ensureLogWorkCurrent(expiresAt); err != nil {
		return containerlog.Output{}, err
	}

	var inspected struct {
		Config struct {
			TTY bool `json:"Tty"`
		} `json:"Config"`
	}
	inspectPath := "/v" + version + "/containers/" +
		url.PathEscape(selected.ID) + "/json"
	if err := client.getJSON(
		workContext,
		inspectPath,
		maximumInspectResponse,
		&inspected); err != nil {
		if hasDockerStatus(err, http.StatusNotFound) {
			return containerlog.Output{}, containerlog.ReadError{
				Kind: containerlog.ReadNotFound,
			}
		}
		return containerlog.Output{}, unavailableLogError()
	}
	if err := client.ensureLogWorkCurrent(expiresAt); err != nil {
		return containerlog.Output{}, err
	}

	query := url.Values{}
	query.Set("stdout", "1")
	query.Set("stderr", "1")
	query.Set("timestamps", "1")
	query.Set("follow", "0")
	query.Set("tail", fmt.Sprintf("%d", tail))
	logsPath := "/v" + version + "/containers/" +
		url.PathEscape(selected.ID) + "/logs?" + query.Encode()
	raw, truncated, err := client.getBoundedRaw(
		workContext,
		logsPath,
		containerlog.MaximumRawBytes)
	if err != nil {
		if hasDockerStatus(err, http.StatusNotFound) {
			return containerlog.Output{}, containerlog.ReadError{
				Kind: containerlog.ReadNotFound,
			}
		}
		return containerlog.Output{}, unavailableLogError()
	}
	output, err := containerlog.DecodeDockerStream(
		raw,
		inspected.Config.TTY,
		tail,
		truncated)
	if err != nil {
		return containerlog.Output{}, unavailableLogError()
	}
	return output, nil
}

func (client *Client) ensureLogWorkCurrent(expiresAt time.Time) error {
	if err := containerlog.ValidateExpiry(expiresAt, client.currentTime()); err != nil {
		return unavailableLogError()
	}
	return nil
}

func (client *Client) getBoundedRaw(
	ctx context.Context,
	path string,
	maximumBytes int64,
) ([]byte, bool, error) {
	request, err := http.NewRequestWithContext(
		ctx,
		http.MethodGet,
		"http://docker"+path,
		nil)
	if err != nil {
		return nil, false, err
	}
	response, err := client.httpClient.Do(request)
	if err != nil {
		return nil, false, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		_, _ = io.Copy(io.Discard, io.LimitReader(response.Body, 4096))
		return nil, false, dockerStatusError{statusCode: response.StatusCode}
	}
	raw, err := io.ReadAll(io.LimitReader(response.Body, maximumBytes+1))
	if err != nil {
		return nil, false, err
	}
	truncated := int64(len(raw)) > maximumBytes
	if truncated {
		raw = raw[:maximumBytes]
	}
	return raw, truncated, nil
}

type dockerStatusError struct {
	statusCode int
}

func (statusError dockerStatusError) Error() string {
	return fmt.Sprintf("Docker API returned status %d", statusError.statusCode)
}

func hasDockerStatus(err error, status int) bool {
	var statusError dockerStatusError
	return errors.As(err, &statusError) && statusError.statusCode == status
}

func unavailableLogError() error {
	return containerlog.ReadError{Kind: containerlog.ReadUnavailable}
}
