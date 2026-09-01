package recovery

import (
	"testing"
	"time"
)

var modelTestNow = time.Date(2026, 9, 1, 12, 0, 0, 0, time.UTC)

func TestWorkValidateAcceptsOnlyBoundedRhaomiRestart(t *testing.T) {
	t.Parallel()
	valid := Work{
		RequestID: "10000000-0000-4000-8000-000000000119",
		Project:   ProjectRhaomi,
		Target:    TargetBackend,
		Action:    ActionRestart,
		ExpiresAt: modelTestNow.Add(10 * time.Second),
	}
	if err := valid.Validate(modelTestNow); err != nil {
		t.Fatalf("valid work rejected: %v", err)
	}

	tests := map[string]Work{
		"identifier": withWork(valid, func(work *Work) { work.RequestID = "invalid" }),
		"project":    withWork(valid, func(work *Work) { work.Project = "other" }),
		"target":     withWork(valid, func(work *Work) { work.Target = "arbitrary" }),
		"action":     withWork(valid, func(work *Work) { work.Action = "SHELL" }),
		"expired":    withWork(valid, func(work *Work) { work.ExpiresAt = modelTestNow }),
		"long expiry": withWork(valid, func(work *Work) {
			work.ExpiresAt = modelTestNow.Add(MaximumExpiryHorizon + time.Nanosecond)
		}),
	}
	for name, work := range tests {
		work := work
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			if err := work.Validate(modelTestNow); err == nil {
				t.Fatal("invalid work was accepted")
			}
		})
	}
}

func TestResultValidateRequiresConsistentBoundedEvidence(t *testing.T) {
	t.Parallel()
	valid := Result{
		RequestID:    "10000000-0000-4000-8000-000000000119",
		Status:       StatusApplied,
		ReasonCode:   ReasonRecoveryApplied,
		StartedAt:    modelTestNow,
		FinishedAt:   modelTestNow.Add(time.Second),
		PreHealth:    HealthDown,
		PostHealth:   HealthUp,
		RestartCount: 1,
	}
	if err := valid.Validate(); err != nil {
		t.Fatalf("valid result rejected: %v", err)
	}

	tests := map[string]Result{
		"reason": withResult(valid, func(result *Result) {
			result.ReasonCode = ReasonRecoveryFailed
		}),
		"false applied": withResult(valid, func(result *Result) {
			result.PostHealth = HealthDown
		}),
		"restart count": withResult(valid, func(result *Result) {
			result.RestartCount = 2
		}),
		"timestamps": withResult(valid, func(result *Result) {
			result.FinishedAt = result.StartedAt.Add(-time.Second)
		}),
	}
	for name, result := range tests {
		result := result
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			if err := result.Validate(); err == nil {
				t.Fatal("invalid result was accepted")
			}
		})
	}
}

func withWork(work Work, change func(*Work)) Work {
	change(&work)
	return work
}

func withResult(result Result, change func(*Result)) Result {
	change(&result)
	return result
}
