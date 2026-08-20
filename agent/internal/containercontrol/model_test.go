package containercontrol

import (
	"testing"
	"time"
)

var modelTestNow = time.Date(2026, 8, 20, 12, 0, 0, 0, time.UTC)

func TestWorkValidateAcceptsOnlyBoundedFixedContract(t *testing.T) {
	t.Parallel()
	for _, operation := range []Operation{
		OperationStart,
		OperationStop,
		OperationRestart,
	} {
		work := validWork(operation, modelTestNow.Add(15*time.Second))
		if err := work.Validate(modelTestNow); err != nil {
			t.Fatalf("Validate(%s) returned an error: %v", operation, err)
		}
	}
}

func TestWorkValidateRejectsInvalidIdentityProjectOperationAndExpiry(t *testing.T) {
	t.Parallel()
	tests := map[string]Work{
		"invalid request": func() Work {
			work := validWork(OperationStart, modelTestNow.Add(time.Second))
			work.RequestID = "not-a-uuid"
			return work
		}(),
		"invalid container": func() Work {
			work := validWork(OperationStart, modelTestNow.Add(time.Second))
			work.ContainerID = "0123456789AB"
			return work
		}(),
		"invalid project": func() Work {
			work := validWork(OperationStart, modelTestNow.Add(time.Second))
			work.ComposeProject = "Example"
			return work
		}(),
		"protected project": func() Work {
			work := validWork(OperationStart, modelTestNow.Add(time.Second))
			work.ComposeProject = "homeops"
			return work
		}(),
		"invalid operation": validWork("REMOVE", modelTestNow.Add(time.Second)),
		"expired":           validWork(OperationStart, modelTestNow),
		"far future": validWork(
			OperationStart,
			modelTestNow.Add(MaximumExpiryHorizon+time.Nanosecond)),
		"missing expiry": validWork(OperationStart, time.Time{}),
	}
	for name, work := range tests {
		work := work
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			if err := work.Validate(modelTestNow); err == nil {
				t.Fatal("Validate accepted invalid control work")
			}
		})
	}
}

func TestResultValidateEnforcesStatusReasonMatrix(t *testing.T) {
	t.Parallel()
	valid := []Outcome{
		{StatusApplied, ReasonApplied},
		{StatusNoop, ReasonAlreadyRunning},
		{StatusNoop, ReasonAlreadyStopped},
		{StatusDenied, ReasonNotManaged},
		{StatusFailed, ReasonDockerRejected},
		{StatusOutcomeUnknown, ReasonDockerOutcomeUnknown},
		{StatusExpired, ReasonWorkExpired},
	}
	for _, outcome := range valid {
		result := Result{
			RequestID: validWork(OperationStart, modelTestNow.Add(time.Second)).RequestID,
			Status:    outcome.Status,
			Reason:    outcome.ReasonCode,
			Finished:  modelTestNow,
		}
		if err := result.Validate(); err != nil {
			t.Fatalf("Validate(%#v) returned an error: %v", result, err)
		}
	}

	invalid := Result{
		RequestID: validWork(OperationStart, modelTestNow.Add(time.Second)).RequestID,
		Status:    StatusApplied,
		Reason:    ReasonDockerRejected,
		Finished:  modelTestNow,
	}
	if err := invalid.Validate(); err == nil {
		t.Fatal("Validate accepted inconsistent status and reason")
	}
}

func validWork(operation Operation, expiresAt time.Time) Work {
	return Work{
		RequestID:      "10000000-0000-4000-8000-000000000001",
		ContainerID:    "0123456789ab",
		ComposeProject: "example",
		Operation:      operation,
		ExpiresAt:      expiresAt,
	}
}
