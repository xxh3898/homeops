package spool

import (
	"errors"
	"os"
	"testing"
)

func TestDrainRemovesOnlyDeliveredPayloads(t *testing.T) {
	t.Parallel()
	store, err := New(t.TempDir(), 3)
	if err != nil {
		t.Fatalf("New returned an error: %v", err)
	}
	if err := store.Store("20260804T120000Z-first", []byte(`{"id":1}`)); err != nil {
		t.Fatalf("Store first payload: %v", err)
	}
	if err := store.Store("20260804T120001Z-second", []byte(`{"id":2}`)); err != nil {
		t.Fatalf("Store second payload: %v", err)
	}
	deliveries := 0
	expectedFailure := errors.New("delivery unavailable")

	err = store.Drain(func(payload []byte) error {
		deliveries++
		if deliveries == 2 {
			return expectedFailure
		}
		return nil
	})

	if !errors.Is(err, expectedFailure) {
		t.Fatalf("Drain error = %v, want %v", err, expectedFailure)
	}
	files, readErr := os.ReadDir(store.directory)
	if readErr != nil {
		t.Fatalf("ReadDir: %v", readErr)
	}
	if len(files) != 1 || files[0].Name() != "20260804T120001Z-second.json" {
		t.Fatalf("remaining files = %v, want second payload only", files)
	}
}

func TestStoreRefusesToEvictWhenSpoolIsFull(t *testing.T) {
	t.Parallel()
	store, err := New(t.TempDir(), 1)
	if err != nil {
		t.Fatalf("New returned an error: %v", err)
	}
	if err := store.Store("20260804T120000Z-first", []byte(`{"id":1}`)); err != nil {
		t.Fatalf("Store first payload: %v", err)
	}

	err = store.Store("20260804T120001Z-second", []byte(`{"id":2}`))

	if !errors.Is(err, ErrFull) {
		t.Fatalf("Store error = %v, want ErrFull", err)
	}
}

func TestDrainQuarantinesPermanentlyRejectedPayload(t *testing.T) {
	t.Parallel()
	store, err := New(t.TempDir(), 2)
	if err != nil {
		t.Fatalf("New returned an error: %v", err)
	}
	if err := store.Store("20260804T120000Z-first", []byte(`{"id":1}`)); err != nil {
		t.Fatalf("Store payload: %v", err)
	}

	err = store.Drain(func([]byte) error {
		return permanentTestError{}
	})

	if err == nil {
		t.Fatal("Drain returned nil error for a permanently rejected payload")
	}
	entries, readErr := os.ReadDir(store.directory)
	if readErr != nil {
		t.Fatalf("ReadDir: %v", readErr)
	}
	if len(entries) != 1 ||
		entries[0].Name() != ".rejected-20260804T120000Z-first.json" {
		t.Fatalf("quarantined entries = %v, want one rejected payload", entries)
	}
	if storeErr := store.Store(
		"20260804T120001Z-second",
		[]byte(`{"id":2}`)); storeErr != nil {
		t.Fatalf("Store second payload: %v", storeErr)
	}
	if storeErr := store.Store(
		"20260804T120002Z-third",
		[]byte(`{"id":3}`)); !errors.Is(storeErr, ErrFull) {
		t.Fatalf("Store error = %v, want rejected payload to count toward capacity", storeErr)
	}
}

func TestSafeNameRejectsPathCharacters(t *testing.T) {
	t.Parallel()
	if safeName("../../snapshot") {
		t.Fatal("safeName accepted path traversal characters")
	}
	if !safeName("20260804T120000000000000Z-snapshot") {
		t.Fatal("safeName rejected the Agent timestamp naming format")
	}
}

type permanentTestError struct{}

func (permanentTestError) Error() string {
	return "permanent test rejection"
}

func (permanentTestError) Permanent() bool {
	return true
}
