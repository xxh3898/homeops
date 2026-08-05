package collector

import (
	"strings"
	"testing"
)

func TestParseCPUUsageUsesLastSample(t *testing.T) {
	t.Parallel()
	output := "CPU usage: 10.00% user, 5.00% sys, 85.00% idle\n" +
		"CPU usage: 20.00% user, 10.00% sys, 70.00% idle\n"

	usage, err := parseCPUUsage(output)

	if err != nil {
		t.Fatalf("parseCPUUsage returned an error: %v", err)
	}
	if usage != 30 {
		t.Fatalf("usage = %v, want 30", usage)
	}
}

func TestParseCPUUsageRejectsUnexpectedOutput(t *testing.T) {
	t.Parallel()
	if _, err := parseCPUUsage("no CPU sample"); err == nil {
		t.Fatal("parseCPUUsage returned nil error for malformed output")
	}
}

func TestParseMemoryUsedAddsActiveAndWiredPages(t *testing.T) {
	t.Parallel()
	output := "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n" +
		"Pages free: 10.\n" +
		"Pages active: 25.\n" +
		"Pages inactive: 30.\n" +
		"Pages wired down: 10.\n" +
		"Pages speculative: 5.\n" +
		"Pages occupied by compressor: 20.\n"
	total := uint64(100 * 16384)

	used, err := parseMemoryUsed(output, total)

	if err != nil {
		t.Fatalf("parseMemoryUsed returned an error: %v", err)
	}
	want := uint64(35 * 16384)
	if used != want {
		t.Fatalf("used = %d, want %d", used, want)
	}
}

func TestParseMemoryUsedRequiresActiveAndWiredPages(t *testing.T) {
	t.Parallel()
	tests := []struct {
		name   string
		output string
	}{
		{
			name: "active missing",
			output: "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n" +
				"Pages wired down: 10.\n",
		},
		{
			name: "wired missing",
			output: "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n" +
				"Pages active: 25.\n",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			t.Parallel()
			_, err := parseMemoryUsed(test.output, 100*16384)
			if err == nil || !strings.Contains(err.Error(), "active or wired pages not found") {
				t.Fatalf("error = %v, want missing pages error", err)
			}
		})
	}
}

func TestParseMemoryUsedRejectsDuplicateRequiredPageCount(t *testing.T) {
	t.Parallel()
	output := "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n" +
		"Pages active: 20.\n" +
		"Pages active: 25.\n" +
		"Pages wired down: 10.\n"

	_, err := parseMemoryUsed(output, 100*16384)

	if err == nil || !strings.Contains(err.Error(), "duplicate Pages active") {
		t.Fatalf("error = %v, want duplicate active pages error", err)
	}
}

func TestParseMemoryUsedRejectsPageCountOverflow(t *testing.T) {
	t.Parallel()
	output := "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n" +
		"Pages active: 18446744073709551615.\n" +
		"Pages wired down: 1.\n"

	_, err := parseMemoryUsed(output, ^uint64(0))

	if err == nil || !strings.Contains(err.Error(), "used page count overflows") {
		t.Fatalf("error = %v, want page count overflow error", err)
	}
}

func TestParseMemoryUsedRejectsByteCountOverflow(t *testing.T) {
	t.Parallel()
	output := "Mach Virtual Memory Statistics: (page size of 2 bytes)\n" +
		"Pages active: 9223372036854775808.\n" +
		"Pages wired down: 0.\n"

	_, err := parseMemoryUsed(output, ^uint64(0))

	if err == nil || !strings.Contains(err.Error(), "used byte count overflows") {
		t.Fatalf("error = %v, want byte count overflow error", err)
	}
}

func TestParseMemoryUsedRejectsZeroPageSize(t *testing.T) {
	t.Parallel()
	output := "Mach Virtual Memory Statistics: (page size of 0 bytes)\n" +
		"Pages active: 25.\n" +
		"Pages wired down: 10.\n"

	_, err := parseMemoryUsed(output, 100)

	if err == nil || !strings.Contains(err.Error(), "page size must be positive") {
		t.Fatalf("error = %v, want positive page size error", err)
	}
}

func TestParseMemoryUsedRejectsUsageAboveTotalMemory(t *testing.T) {
	t.Parallel()
	output := "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n" +
		"Pages active: 90.\n" +
		"Pages wired down: 20.\n"

	_, err := parseMemoryUsed(output, 100*16384)

	if err == nil || !strings.Contains(err.Error(), "used memory exceeds total") {
		t.Fatalf("error = %v, want usage above total error", err)
	}
}
