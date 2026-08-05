package collector

import "testing"

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

func TestParseMemoryUsedSubtractsFreeAndSpeculativePages(t *testing.T) {
	t.Parallel()
	output := "Mach Virtual Memory Statistics: (page size of 16384 bytes)\n" +
		"Pages free: 10.\n" +
		"Pages active: 70.\n" +
		"Pages speculative: 5.\n"
	total := uint64(100 * 16384)

	used, err := parseMemoryUsed(output, total)

	if err != nil {
		t.Fatalf("parseMemoryUsed returned an error: %v", err)
	}
	want := uint64(85 * 16384)
	if used != want {
		t.Fatalf("used = %d, want %d", used, want)
	}
}
