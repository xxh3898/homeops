package recovery

import (
	"context"
	"fmt"
	"reflect"
	"testing"
	"time"
)

func TestFixedExecutorInvokesAllowlistedCapabilityOnceAndProjectsSuccess(t *testing.T) {
	t.Parallel()
	finished := modelTestNow.Add(2 * time.Second)
	runner := &recordingRunner{execution: commandExecution{
		output: capabilityJSON(
			"success",
			ReasonRecoveryApplied,
			HealthDown,
			HealthUp,
			1,
			modelTestNow,
			finished),
		started:  true,
		exitCode: 0,
	}}
	executor := fixedExecutorForTest(runner, finished)

	result := executor.Execute(context.Background(), validWork(), modelTestNow)

	if runner.calls != 1 {
		t.Fatalf("runner calls = %d, want 1", runner.calls)
	}
	if !runner.fixedExecutable ||
		!reflect.DeepEqual(runner.arguments, []string{"restart", "backend"}) ||
		!reflect.DeepEqual(runner.environment, fixedEnvironment) ||
		runner.maximumBytes != MaximumResultBytes {
		t.Fatalf("fixed execution contract was not preserved")
	}
	if result.Status != StatusApplied ||
		result.ReasonCode != ReasonRecoveryApplied ||
		result.RestartCount != 1 ||
		result.PostHealth != HealthUp {
		t.Fatalf("result = %#v", result)
	}
}

func TestFixedExecutorDoesNotInvokeCapabilityWhenWorkIsExpired(t *testing.T) {
	t.Parallel()
	runner := &recordingRunner{}
	executor := fixedExecutorForTest(runner, modelTestNow)
	work := validWork()
	work.ExpiresAt = modelTestNow

	result := executor.Execute(context.Background(), work, modelTestNow)

	if runner.calls != 0 || result.Status != StatusExpired ||
		result.ReasonCode != ReasonWorkExpired || result.RestartCount != 0 {
		t.Fatalf("calls/result = %d/%#v", runner.calls, result)
	}
}

func TestFixedExecutorMapsPreconditionFailureToNoopWithoutRestart(t *testing.T) {
	t.Parallel()
	result := executeCapabilityResult(
		t,
		"failed",
		ReasonRecoveryLocked,
		HealthUnknown,
		HealthUnknown,
		0,
		1)
	if result.Status != StatusNoop || result.RestartCount != 0 {
		t.Fatalf("result = %#v", result)
	}
}

func TestFixedExecutorDoesNotReportFalseSuccessWhenPostHealthFails(t *testing.T) {
	t.Parallel()
	result := executeCapabilityResult(
		t,
		"failed",
		ReasonRecoveryPostHealthFailed,
		HealthDown,
		HealthDown,
		1,
		1)
	if result.Status != StatusFailed || result.ReasonCode != ReasonRecoveryPostHealthFailed {
		t.Fatalf("result = %#v", result)
	}
}

func TestFixedExecutorMapsUnconfirmedRestartToOutcomeUnknownWithoutRetry(t *testing.T) {
	t.Parallel()
	finished := modelTestNow.Add(time.Second)
	runner := &recordingRunner{execution: commandExecution{
		output: capabilityJSON(
			"failed",
			ReasonRecoveryRestartUnconfirmed,
			HealthDown,
			HealthUnknown,
			1,
			modelTestNow,
			finished),
		started:  true,
		exitCode: 1,
	}}
	executor := fixedExecutorForTest(runner, finished)

	result := executor.Execute(context.Background(), validWork(), modelTestNow)

	if runner.calls != 1 || result.Status != StatusOutcomeUnknown ||
		result.ReasonCode != ReasonRecoveryRestartUnconfirmed {
		t.Fatalf("calls/result = %d/%#v", runner.calls, result)
	}
}

func TestFixedExecutorRejectsMalformedOversizeAndTimeoutResultsAsUnknown(t *testing.T) {
	t.Parallel()
	tests := map[string]commandExecution{
		"malformed": {output: []byte(`{"status":"success"}`), started: true, exitCode: 0},
		"overflow":  {output: []byte(`{}`), started: true, exitCode: 0, overflow: true},
		"timeout":   {started: true, exitCode: -1, timedOut: true},
	}
	for name, execution := range tests {
		execution := execution
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			runner := &recordingRunner{execution: execution}
			executor := fixedExecutorForTest(runner, modelTestNow.Add(time.Second))
			result := executor.Execute(context.Background(), validWork(), modelTestNow)
			if runner.calls != 1 || result.Status != StatusOutcomeUnknown ||
				result.RestartCount != 0 {
				t.Fatalf("calls/result = %d/%#v", runner.calls, result)
			}
		})
	}
}

func TestFixedExecutorFailsClosedWhenCapabilityIsUnavailable(t *testing.T) {
	t.Parallel()
	runner := &recordingRunner{execution: commandExecution{exitCode: -1}}
	executor := fixedExecutorForTest(runner, modelTestNow)

	result := executor.Execute(context.Background(), validWork(), modelTestNow)

	if runner.calls != 1 || result.Status != StatusFailed ||
		result.ReasonCode != ReasonCapabilityUnavailable || result.RestartCount != 0 {
		t.Fatalf("calls/result = %d/%#v", runner.calls, result)
	}
}

func executeCapabilityResult(
	t *testing.T,
	status string,
	reason ReasonCode,
	preHealth Health,
	postHealth Health,
	restartCount int,
	exitCode int,
) Result {
	t.Helper()
	finished := modelTestNow.Add(time.Second)
	runner := &recordingRunner{execution: commandExecution{
		output: capabilityJSON(
			status,
			reason,
			preHealth,
			postHealth,
			restartCount,
			modelTestNow,
			finished),
		started:  true,
		exitCode: exitCode,
	}}
	executor := fixedExecutorForTest(runner, finished)
	return executor.Execute(context.Background(), validWork(), modelTestNow)
}

func capabilityJSON(
	status string,
	code ReasonCode,
	preHealth Health,
	postHealth Health,
	restartCount int,
	startedAt time.Time,
	finishedAt time.Time,
) []byte {
	return []byte(fmt.Sprintf(
		`{"schemaVersion":1,"startedAt":%q,"finishedAt":%q,`+
			`"service":"backend","operation":"restart","preHealth":%q,`+
			`"postHealth":%q,"restartCount":%d,"status":%q,"code":%q}`,
		startedAt.Format(time.RFC3339Nano),
		finishedAt.Format(time.RFC3339Nano),
		preHealth,
		postHealth,
		restartCount,
		status,
		code))
}

func validWork() Work {
	return Work{
		RequestID: "10000000-0000-4000-8000-000000000119",
		Project:   ProjectRhaomi,
		Target:    TargetBackend,
		Action:    ActionRestart,
		ExpiresAt: modelTestNow.Add(10 * time.Second),
	}
}

func fixedExecutorForTest(runner commandRunner, now time.Time) *FixedExecutor {
	return &FixedExecutor{
		runner: runner,
		now:    func() time.Time { return now },
	}
}

type recordingRunner struct {
	execution       commandExecution
	calls           int
	fixedExecutable bool
	arguments       []string
	environment     []string
	maximumBytes    int
}

func (runner *recordingRunner) Run(
	_ context.Context,
	executable string,
	arguments []string,
	environment []string,
	maximumBytes int,
) commandExecution {
	runner.calls++
	runner.fixedExecutable = executable == rhaomiRecoveryExecutable
	runner.arguments = append([]string(nil), arguments...)
	runner.environment = append([]string(nil), environment...)
	runner.maximumBytes = maximumBytes
	return runner.execution
}
