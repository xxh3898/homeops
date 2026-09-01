package recovery

import (
	"errors"
	"regexp"
	"time"
)

const (
	MaximumExpiryHorizon = 11 * time.Second
	ResultReportingGrace = 185 * time.Second
	MaximumResultBytes   = 4 * 1024
)

var requestIDPattern = regexp.MustCompile(
	`^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)

type Project string

const ProjectRhaomi Project = "rhaomi"

type Target string

const (
	TargetRhaomiWeb Target = "rhaomi-web"
	TargetBackend   Target = "backend"
)

type Action string

const ActionRestart Action = "RESTART"

type Work struct {
	RequestID string    `json:"requestId"`
	Project   Project   `json:"project"`
	Target    Target    `json:"target"`
	Action    Action    `json:"action"`
	ExpiresAt time.Time `json:"expiresAt"`
}

func (work Work) Validate(now time.Time) error {
	if !requestIDPattern.MatchString(work.RequestID) {
		return errors.New("automatic recovery request identifier is invalid")
	}
	if work.Project != ProjectRhaomi {
		return errors.New("automatic recovery project is invalid")
	}
	if work.Target != TargetRhaomiWeb && work.Target != TargetBackend {
		return errors.New("automatic recovery target is invalid")
	}
	if work.Action != ActionRestart {
		return errors.New("automatic recovery action is invalid")
	}
	return ValidateExpiry(work.ExpiresAt, now)
}

func ValidateExpiry(expiresAt time.Time, now time.Time) error {
	now = now.UTC()
	if expiresAt.IsZero() || !now.Before(expiresAt) {
		return errors.New("automatic recovery request has expired")
	}
	if expiresAt.UTC().After(now.Add(MaximumExpiryHorizon)) {
		return errors.New("automatic recovery request expiration is invalid")
	}
	return nil
}

func ResultDeliveryDeadline(expiresAt time.Time, now time.Time) (time.Time, error) {
	now = now.UTC()
	if expiresAt.IsZero() || expiresAt.UTC().After(now.Add(MaximumExpiryHorizon)) {
		return time.Time{}, errors.New("automatic recovery result expiration is invalid")
	}
	deadline := expiresAt.UTC().Add(ResultReportingGrace)
	if !now.Before(deadline) {
		return time.Time{}, errors.New("automatic recovery result reporting deadline has expired")
	}
	return deadline, nil
}

type Status string

const (
	StatusApplied        Status = "APPLIED"
	StatusNoop           Status = "NOOP"
	StatusFailed         Status = "FAILED"
	StatusOutcomeUnknown Status = "OUTCOME_UNKNOWN"
	StatusExpired        Status = "EXPIRED"
)

type ReasonCode string

const (
	ReasonRecoveryApplied            ReasonCode = "RECOVERY_APPLIED"
	ReasonRecoveryInputInvalid       ReasonCode = "RECOVERY_INPUT_INVALID"
	ReasonRecoveryLocked             ReasonCode = "RECOVERY_LOCKED"
	ReasonRecoveryLockInvalid        ReasonCode = "RECOVERY_LOCK_INVALID"
	ReasonRecoveryLockReleaseFailed  ReasonCode = "RECOVERY_LOCK_RELEASE_FAILED"
	ReasonRecoveryTargetInvalid      ReasonCode = "RECOVERY_TARGET_INVALID"
	ReasonRecoveryTargetUnavailable  ReasonCode = "RECOVERY_TARGET_UNAVAILABLE"
	ReasonRecoveryIdentityChanged    ReasonCode = "RECOVERY_IDENTITY_CHANGED"
	ReasonRecoveryPostHealthFailed   ReasonCode = "RECOVERY_POST_HEALTH_FAILED"
	ReasonRecoveryRestartUnconfirmed ReasonCode = "RECOVERY_RESTART_UNCONFIRMED"
	ReasonRecoveryFailed             ReasonCode = "RECOVERY_FAILED"
	ReasonCapabilityUnavailable      ReasonCode = "CAPABILITY_UNAVAILABLE"
	ReasonCapabilityResultInvalid    ReasonCode = "CAPABILITY_RESULT_INVALID"
	ReasonCapabilityTimeout          ReasonCode = "CAPABILITY_TIMEOUT"
	ReasonWorkExpired                ReasonCode = "WORK_EXPIRED"
)

type Health string

const (
	HealthUp      Health = "UP"
	HealthDown    Health = "DOWN"
	HealthUnknown Health = "UNKNOWN"
)

type Result struct {
	RequestID    string     `json:"requestId"`
	Status       Status     `json:"status"`
	ReasonCode   ReasonCode `json:"reasonCode"`
	StartedAt    time.Time  `json:"startedAt"`
	FinishedAt   time.Time  `json:"finishedAt"`
	PreHealth    Health     `json:"preHealth"`
	PostHealth   Health     `json:"postHealth"`
	RestartCount int        `json:"restartCount"`
}

func (result Result) Validate() error {
	if !requestIDPattern.MatchString(result.RequestID) {
		return errors.New("automatic recovery result request identifier is invalid")
	}
	if !validStatusReason(result.Status, result.ReasonCode) {
		return errors.New("automatic recovery result status and reason are invalid")
	}
	if result.StartedAt.IsZero() || result.FinishedAt.Before(result.StartedAt) {
		return errors.New("automatic recovery result timestamps are invalid")
	}
	if !result.PreHealth.valid() || !result.PostHealth.valid() {
		return errors.New("automatic recovery result health is invalid")
	}
	if result.RestartCount < 0 || result.RestartCount > 1 {
		return errors.New("automatic recovery restart count is invalid")
	}
	if result.Status == StatusApplied &&
		(result.RestartCount != 1 || result.PostHealth != HealthUp) {
		return errors.New("applied automatic recovery evidence is invalid")
	}
	if (result.Status == StatusNoop || result.Status == StatusExpired) &&
		result.RestartCount != 0 {
		return errors.New("non-mutating automatic recovery evidence is invalid")
	}
	return nil
}

func (health Health) valid() bool {
	return health == HealthUp || health == HealthDown || health == HealthUnknown
}

func validStatusReason(status Status, reason ReasonCode) bool {
	switch status {
	case StatusApplied:
		return reason == ReasonRecoveryApplied
	case StatusNoop:
		switch reason {
		case ReasonRecoveryInputInvalid,
			ReasonRecoveryLocked,
			ReasonRecoveryLockInvalid,
			ReasonRecoveryTargetInvalid,
			ReasonRecoveryTargetUnavailable:
			return true
		}
	case StatusFailed:
		switch reason {
		case ReasonRecoveryLockReleaseFailed,
			ReasonRecoveryIdentityChanged,
			ReasonRecoveryPostHealthFailed,
			ReasonRecoveryFailed,
			ReasonCapabilityUnavailable:
			return true
		}
	case StatusOutcomeUnknown:
		return reason == ReasonRecoveryRestartUnconfirmed ||
			reason == ReasonCapabilityResultInvalid ||
			reason == ReasonCapabilityTimeout
	case StatusExpired:
		return reason == ReasonWorkExpired
	}
	return false
}
