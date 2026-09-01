package recovery

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"os/exec"
	"time"
)

const MaximumExecutionDuration = 180 * time.Second

const rhaomiRecoveryExecutable = "/private/var/lib/rhaomi/app/bin/recover-rhaomi-service.py"

var fixedEnvironment = []string{
	"PATH=/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin",
	"LANG=C",
	"LC_ALL=C",
}

type FixedExecutor struct {
	runner commandRunner
	now    func() time.Time
}

func NewFixedExecutor() *FixedExecutor {
	return &FixedExecutor{
		runner: osCommandRunner{},
		now:    func() time.Time { return time.Now().UTC() },
	}
}

func (executor *FixedExecutor) Execute(
	ctx context.Context,
	work Work,
	now time.Time,
) Result {
	now = now.UTC().Truncate(time.Microsecond)
	if err := work.Validate(now); err != nil {
		return Result{
			RequestID:    work.RequestID,
			Status:       StatusExpired,
			ReasonCode:   ReasonWorkExpired,
			StartedAt:    now,
			FinishedAt:   now,
			PreHealth:    HealthUnknown,
			PostHealth:   HealthUnknown,
			RestartCount: 0,
		}
	}
	executionContext, cancel := context.WithTimeout(ctx, MaximumExecutionDuration)
	defer cancel()
	execution := executor.runner.Run(
		executionContext,
		rhaomiRecoveryExecutable,
		[]string{"restart", string(work.Target)},
		fixedEnvironment,
		MaximumResultBytes)
	finished := executor.currentTime().Truncate(time.Microsecond)
	if finished.Before(now) {
		finished = now
	}
	if !execution.started {
		return failedResult(
			work.RequestID,
			StatusFailed,
			ReasonCapabilityUnavailable,
			now,
			finished)
	}
	if execution.timedOut {
		return failedResult(
			work.RequestID,
			StatusOutcomeUnknown,
			ReasonCapabilityTimeout,
			now,
			finished)
	}
	if execution.overflow || execution.err != nil && execution.exitCode < 0 {
		return failedResult(
			work.RequestID,
			StatusOutcomeUnknown,
			ReasonCapabilityResultInvalid,
			now,
			finished)
	}
	parsed, err := parseCapabilityResult(execution.output)
	if err != nil || !parsed.validFor(work, now, finished, execution.exitCode) {
		return failedResult(
			work.RequestID,
			StatusOutcomeUnknown,
			ReasonCapabilityResultInvalid,
			now,
			finished)
	}
	status, reason, ok := projectCapabilityResult(parsed, execution.exitCode)
	if !ok {
		return failedResult(
			work.RequestID,
			StatusOutcomeUnknown,
			ReasonCapabilityResultInvalid,
			now,
			finished)
	}
	return Result{
		RequestID:    work.RequestID,
		Status:       status,
		ReasonCode:   reason,
		StartedAt:    parsed.StartedAt,
		FinishedAt:   parsed.FinishedAt,
		PreHealth:    parsed.PreHealth,
		PostHealth:   parsed.PostHealth,
		RestartCount: parsed.RestartCount,
	}
}

func (executor *FixedExecutor) currentTime() time.Time {
	if executor.now != nil {
		return executor.now().UTC()
	}
	return time.Now().UTC()
}

func failedResult(
	requestID string,
	status Status,
	reason ReasonCode,
	startedAt time.Time,
	finishedAt time.Time,
) Result {
	return Result{
		RequestID:    requestID,
		Status:       status,
		ReasonCode:   reason,
		StartedAt:    startedAt,
		FinishedAt:   finishedAt,
		PreHealth:    HealthUnknown,
		PostHealth:   HealthUnknown,
		RestartCount: 0,
	}
}

type capabilityResult struct {
	SchemaVersion int        `json:"schemaVersion"`
	StartedAt     time.Time  `json:"startedAt"`
	FinishedAt    time.Time  `json:"finishedAt"`
	Service       *string    `json:"service"`
	Operation     string     `json:"operation"`
	PreHealth     Health     `json:"preHealth"`
	PostHealth    Health     `json:"postHealth"`
	RestartCount  int        `json:"restartCount"`
	Status        string     `json:"status"`
	Code          ReasonCode `json:"code"`
}

func parseCapabilityResult(payload []byte) (capabilityResult, error) {
	decoder := json.NewDecoder(bytes.NewReader(payload))
	decoder.DisallowUnknownFields()
	var result capabilityResult
	if err := decoder.Decode(&result); err != nil {
		return capabilityResult{}, err
	}
	if decoder.Decode(&struct{}{}) != io.EOF {
		return capabilityResult{}, errors.New("automatic recovery result has trailing data")
	}
	return result, nil
}

func (result capabilityResult) validFor(
	work Work,
	commandStartedAt time.Time,
	commandFinishedAt time.Time,
	exitCode int,
) bool {
	if result.SchemaVersion != 1 || result.Service == nil ||
		*result.Service != string(work.Target) || result.Operation != "restart" ||
		!result.PreHealth.valid() || !result.PostHealth.valid() ||
		result.RestartCount < 0 || result.RestartCount > 1 ||
		result.StartedAt.IsZero() || result.FinishedAt.Before(result.StartedAt) {
		return false
	}
	if result.StartedAt.Before(commandStartedAt.Add(-time.Second)) ||
		result.FinishedAt.After(commandFinishedAt.Add(time.Second)) {
		return false
	}
	return exitCode == 0 || exitCode == 1
}

func projectCapabilityResult(
	result capabilityResult,
	exitCode int,
) (Status, ReasonCode, bool) {
	if exitCode == 0 {
		if result.Status == "success" && result.Code == ReasonRecoveryApplied &&
			result.RestartCount == 1 && result.PostHealth == HealthUp {
			return StatusApplied, result.Code, true
		}
		return "", "", false
	}
	if result.Status != "failed" {
		return "", "", false
	}
	switch result.Code {
	case ReasonRecoveryInputInvalid,
		ReasonRecoveryLocked,
		ReasonRecoveryLockInvalid,
		ReasonRecoveryTargetInvalid,
		ReasonRecoveryTargetUnavailable:
		if result.RestartCount != 0 {
			return "", "", false
		}
		return StatusNoop, result.Code, true
	case ReasonRecoveryRestartUnconfirmed:
		return StatusOutcomeUnknown, result.Code, true
	case ReasonRecoveryLockReleaseFailed,
		ReasonRecoveryIdentityChanged,
		ReasonRecoveryPostHealthFailed,
		ReasonRecoveryFailed:
		return StatusFailed, result.Code, true
	default:
		return "", "", false
	}
}

type commandExecution struct {
	output   []byte
	started  bool
	exitCode int
	timedOut bool
	overflow bool
	err      error
}

type commandRunner interface {
	Run(
		context.Context,
		string,
		[]string,
		[]string,
		int,
	) commandExecution
}

type osCommandRunner struct{}

func (osCommandRunner) Run(
	ctx context.Context,
	executable string,
	arguments []string,
	environment []string,
	maximumBytes int,
) commandExecution {
	command := exec.CommandContext(ctx, executable, arguments...)
	command.Env = append([]string(nil), environment...)
	output := &boundedBuffer{maximum: maximumBytes}
	command.Stdout = output
	command.Stderr = io.Discard
	if err := command.Start(); err != nil {
		return commandExecution{exitCode: -1, err: err}
	}
	err := command.Wait()
	exitCode := 0
	if err != nil {
		var exitError *exec.ExitError
		if errors.As(err, &exitError) {
			exitCode = exitError.ExitCode()
		} else {
			exitCode = -1
		}
	}
	return commandExecution{
		output:   append([]byte(nil), output.Bytes()...),
		started:  true,
		exitCode: exitCode,
		timedOut: ctx.Err() != nil,
		overflow: output.overflow,
		err:      err,
	}
}

type boundedBuffer struct {
	bytes.Buffer
	maximum  int
	overflow bool
}

func (buffer *boundedBuffer) Write(payload []byte) (int, error) {
	written := len(payload)
	remaining := buffer.maximum - buffer.Len()
	if remaining <= 0 {
		buffer.overflow = true
		return written, nil
	}
	if len(payload) > remaining {
		buffer.overflow = true
		payload = payload[:remaining]
	}
	_, _ = buffer.Buffer.Write(payload)
	return written, nil
}
