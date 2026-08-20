package containercontrol

import (
	"errors"
	"regexp"
	"time"
)

const (
	MaximumExpiryHorizon = 16 * time.Second
	ResultReportingGrace = 15 * time.Second
)

var (
	requestIDPattern = regexp.MustCompile(
		`^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	containerIDPattern     = regexp.MustCompile(`^[0-9a-f]{12}$`)
	fullContainerIDPattern = regexp.MustCompile(`^[0-9a-f]{12,64}$`)
	projectPattern         = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]{0,62}$`)
)

type Operation string

const (
	OperationStart   Operation = "START"
	OperationStop    Operation = "STOP"
	OperationRestart Operation = "RESTART"
)

type Work struct {
	RequestID      string    `json:"requestId"`
	ContainerID    string    `json:"containerId"`
	ComposeProject string    `json:"composeProject"`
	Operation      Operation `json:"operation"`
	ExpiresAt      time.Time `json:"expiresAt"`
}

func (work Work) Validate(now time.Time) error {
	if !requestIDPattern.MatchString(work.RequestID) {
		return errors.New("control request identifier is invalid")
	}
	if !ValidContainerID(work.ContainerID) {
		return errors.New("container identifier is invalid")
	}
	if !ValidProject(work.ComposeProject) || work.ComposeProject == "homeops" {
		return errors.New("control project is invalid")
	}
	if !work.Operation.Valid() {
		return errors.New("control operation is invalid")
	}
	return ValidateExpiry(work.ExpiresAt, now)
}

func (operation Operation) Valid() bool {
	return operation == OperationStart ||
		operation == OperationStop ||
		operation == OperationRestart
}

func ValidateExpiry(expiresAt time.Time, now time.Time) error {
	now = now.UTC()
	if expiresAt.IsZero() {
		return errors.New("control request expiration is invalid")
	}
	expiresAt = expiresAt.UTC()
	if !now.Before(expiresAt) {
		return errors.New("control request has expired")
	}
	if expiresAt.After(now.Add(MaximumExpiryHorizon)) {
		return errors.New("control request expiration is invalid")
	}
	return nil
}

func ResultDeliveryDeadline(expiresAt time.Time, now time.Time) (time.Time, error) {
	now = now.UTC()
	if expiresAt.IsZero() {
		return time.Time{}, errors.New("control result expiration is invalid")
	}
	expiresAt = expiresAt.UTC()
	if expiresAt.After(now.Add(MaximumExpiryHorizon)) {
		return time.Time{}, errors.New("control result expiration is invalid")
	}
	deadline := expiresAt.Add(ResultReportingGrace)
	if !now.Before(deadline) {
		return time.Time{}, errors.New("control result reporting deadline has expired")
	}
	return deadline, nil
}

func ValidContainerID(identifier string) bool {
	return containerIDPattern.MatchString(identifier)
}

func ValidFullContainerID(identifier string) bool {
	return fullContainerIDPattern.MatchString(identifier)
}

func ValidProject(project string) bool {
	return projectPattern.MatchString(project)
}

type Status string

const (
	StatusApplied        Status = "APPLIED"
	StatusNoop           Status = "NOOP"
	StatusDenied         Status = "DENIED"
	StatusFailed         Status = "FAILED"
	StatusOutcomeUnknown Status = "OUTCOME_UNKNOWN"
	StatusExpired        Status = "EXPIRED"
)

type ReasonCode string

const (
	ReasonApplied                    ReasonCode = "APPLIED"
	ReasonAlreadyRunning             ReasonCode = "ALREADY_RUNNING"
	ReasonAlreadyStopped             ReasonCode = "ALREADY_STOPPED"
	ReasonContainerNotFound          ReasonCode = "CONTAINER_NOT_FOUND"
	ReasonAmbiguousIdentifier        ReasonCode = "AMBIGUOUS_IDENTIFIER"
	ReasonNotManaged                 ReasonCode = "NOT_MANAGED"
	ReasonProjectMismatch            ReasonCode = "PROJECT_MISMATCH"
	ReasonProtectedProject           ReasonCode = "PROTECTED_PROJECT"
	ReasonComposeServiceUnavailable  ReasonCode = "COMPOSE_SERVICE_UNAVAILABLE"
	ReasonProtectedService           ReasonCode = "PROTECTED_SERVICE"
	ReasonWritableMount              ReasonCode = "WRITABLE_MOUNT"
	ReasonMountProtectionUnavailable ReasonCode = "MOUNT_PROTECTION_UNAVAILABLE"
	ReasonDockerUnavailable          ReasonCode = "DOCKER_UNAVAILABLE"
	ReasonDockerRejected             ReasonCode = "DOCKER_REJECTED"
	ReasonDockerOutcomeUnknown       ReasonCode = "DOCKER_OUTCOME_UNKNOWN"
	ReasonWorkExpired                ReasonCode = "WORK_EXPIRED"
)

type Outcome struct {
	Status     Status
	ReasonCode ReasonCode
}

func (outcome Outcome) Valid() bool {
	return validStatusReason(outcome.Status, outcome.ReasonCode)
}

type Result struct {
	RequestID string     `json:"requestId"`
	Status    Status     `json:"status"`
	Reason    ReasonCode `json:"reasonCode"`
	Finished  time.Time  `json:"finishedAt"`
}

func (result Result) Validate() error {
	if !requestIDPattern.MatchString(result.RequestID) {
		return errors.New("control result request identifier is invalid")
	}
	if !validStatusReason(result.Status, result.Reason) {
		return errors.New("control result status and reason are invalid")
	}
	if result.Finished.IsZero() {
		return errors.New("control result timestamp is invalid")
	}
	return nil
}

func validStatusReason(status Status, reason ReasonCode) bool {
	switch status {
	case StatusApplied:
		return reason == ReasonApplied
	case StatusNoop:
		return reason == ReasonAlreadyRunning || reason == ReasonAlreadyStopped
	case StatusDenied:
		switch reason {
		case ReasonContainerNotFound,
			ReasonAmbiguousIdentifier,
			ReasonNotManaged,
			ReasonProjectMismatch,
			ReasonProtectedProject,
			ReasonComposeServiceUnavailable,
			ReasonProtectedService,
			ReasonWritableMount,
			ReasonMountProtectionUnavailable:
			return true
		default:
			return false
		}
	case StatusFailed:
		return reason == ReasonDockerUnavailable || reason == ReasonDockerRejected
	case StatusOutcomeUnknown:
		return reason == ReasonDockerOutcomeUnknown
	case StatusExpired:
		return reason == ReasonWorkExpired
	default:
		return false
	}
}
