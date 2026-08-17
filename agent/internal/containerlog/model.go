package containerlog

import (
	"errors"
	"regexp"
	"time"
)

const (
	MaximumRawBytes     = 256 * 1024
	MaximumLineBytes    = 8 * 1024
	MaximumMessageBytes = 128 * 1024
	DockerTimeout       = 3 * time.Second
)

var (
	requestIDPattern = regexp.MustCompile(
		`^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	containerIDPattern     = regexp.MustCompile(`^[0-9a-f]{12}$`)
	fullContainerIDPattern = regexp.MustCompile(`^[0-9a-f]{12,64}$`)
)

type Work struct {
	RequestID   string `json:"requestId"`
	ContainerID string `json:"containerId"`
	Tail        int    `json:"tail"`
}

func (work Work) Validate() error {
	if !requestIDPattern.MatchString(work.RequestID) {
		return errors.New("log request identifier is invalid")
	}
	if !containerIDPattern.MatchString(work.ContainerID) {
		return errors.New("container identifier is invalid")
	}
	if !AllowedTail(work.Tail) {
		return errors.New("log tail is invalid")
	}
	return nil
}

func AllowedTail(tail int) bool {
	return tail == 50 || tail == 100 || tail == 200
}

func ValidContainerID(identifier string) bool {
	return containerIDPattern.MatchString(identifier)
}

func ValidFullContainerID(identifier string) bool {
	return fullContainerIDPattern.MatchString(identifier)
}

type Stream string

const (
	StreamStdout   Stream = "STDOUT"
	StreamStderr   Stream = "STDERR"
	StreamCombined Stream = "COMBINED"
)

type Line struct {
	Timestamp *time.Time `json:"timestamp,omitempty"`
	Stream    Stream     `json:"stream"`
	Message   string     `json:"message"`
}

type Status string

const (
	StatusSuccess        Status = "SUCCESS"
	StatusNotFound       Status = "NOT_FOUND"
	StatusAmbiguous      Status = "AMBIGUOUS"
	StatusNotAllowed     Status = "NOT_ALLOWED"
	StatusUnavailable    Status = "UNAVAILABLE"
	StatusInvalidRequest Status = "INVALID_REQUEST"
)

type Result struct {
	RequestID string `json:"requestId"`
	Status    Status `json:"status"`
	Lines     []Line `json:"lines"`
	Truncated bool   `json:"truncated"`
}

type Output struct {
	Lines     []Line
	Truncated bool
}

type ReadErrorKind string

const (
	ReadNotFound    ReadErrorKind = "NOT_FOUND"
	ReadAmbiguous   ReadErrorKind = "AMBIGUOUS"
	ReadNotAllowed  ReadErrorKind = "NOT_ALLOWED"
	ReadUnavailable ReadErrorKind = "UNAVAILABLE"
)

type ReadError struct {
	Kind ReadErrorKind
}

func (readError ReadError) Error() string {
	return "container logs are unavailable"
}
