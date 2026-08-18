package containerlog

import (
	"testing"
	"time"
)

func TestWorkValidateAcceptsBoundedFutureExpiry(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	work := validWork(now.Add(6 * time.Second))

	if err := work.Validate(now); err != nil {
		t.Fatalf("Validate returned an error: %v", err)
	}
}

func TestWorkValidateRejectsExpiredAndExcessivelyFutureExpiry(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 8, 18, 1, 2, 3, 0, time.UTC)
	for name, expiresAt := range map[string]time.Time{
		"already expired": now,
		"far future":      now.Add(MaximumExpiryHorizon + time.Nanosecond),
		"missing":         {},
	} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			if err := validWork(expiresAt).Validate(now); err == nil {
				t.Fatal("Validate accepted invalid expiration")
			}
		})
	}
}

func validWork(expiresAt time.Time) Work {
	return Work{
		RequestID:   "10000000-0000-4000-8000-000000000001",
		ContainerID: "0123456789ab",
		Tail:        50,
		ExpiresAt:   expiresAt,
	}
}
