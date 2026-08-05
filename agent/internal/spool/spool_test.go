package spool

import (
	"errors"
	"os"
	"path/filepath"
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

	result, err := store.Drain(func(payload []byte) error {
		deliveries++
		if deliveries == 2 {
			return expectedFailure
		}
		return nil
	})

	if !errors.Is(err, expectedFailure) {
		t.Fatalf("Drain error = %v, want %v", err, expectedFailure)
	}
	if result.Delivered != 1 || result.Rejected != 0 {
		t.Fatalf("Drain result = %+v, want one delivery and no rejections", result)
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

func TestDrainQuarantinesPermanentRejectionAndContinues(t *testing.T) {
	t.Parallel()
	store, err := New(t.TempDir(), 2)
	if err != nil {
		t.Fatalf("New returned an error: %v", err)
	}
	if err := store.Store("20260804T120000Z-first", []byte(`{"id":1}`)); err != nil {
		t.Fatalf("Store first payload: %v", err)
	}
	if err := store.Store("20260804T120001Z-second", []byte(`{"id":2}`)); err != nil {
		t.Fatalf("Store second payload: %v", err)
	}

	result, err := store.Drain(func(payload []byte) error {
		if string(payload) == `{"id":1}` {
			return permanentTestError{}
		}
		return nil
	})

	if err != nil {
		t.Fatalf("Drain returned an error: %v", err)
	}
	if result.Delivered != 1 || result.Rejected != 1 {
		t.Fatalf("Drain result = %+v, want one delivery and one rejection", result)
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
		"20260804T120002Z-third",
		[]byte(`{"id":3}`)); storeErr != nil {
		t.Fatalf("Store third payload: %v", storeErr)
	}
	if storeErr := store.Store(
		"20260804T120003Z-fourth",
		[]byte(`{"id":4}`)); !errors.Is(storeErr, ErrFull) {
		t.Fatalf("Store error = %v, want rejected payload to count toward capacity", storeErr)
	}
}

func TestDrainStopsAtTransientFailureAfterPermanentRejection(t *testing.T) {
	t.Parallel()
	store, err := New(t.TempDir(), 3)
	if err != nil {
		t.Fatalf("New returned an error: %v", err)
	}
	for index, name := range []string{
		"20260804T120000Z-first",
		"20260804T120001Z-second",
		"20260804T120002Z-third",
	} {
		payload := []byte{byte('1' + index)}
		if storeErr := store.Store(name, payload); storeErr != nil {
			t.Fatalf("Store payload %d: %v", index+1, storeErr)
		}
	}
	expectedFailure := errors.New("delivery unavailable")
	deliveries := 0

	result, err := store.Drain(func([]byte) error {
		deliveries++
		switch deliveries {
		case 1:
			return permanentTestError{}
		case 2:
			return expectedFailure
		default:
			t.Fatal("Drain attempted a payload after a transient failure")
			return nil
		}
	})

	if !errors.Is(err, expectedFailure) {
		t.Fatalf("Drain error = %v, want %v", err, expectedFailure)
	}
	if result.Delivered != 0 || result.Rejected != 1 {
		t.Fatalf("Drain result = %+v, want no deliveries and one rejection", result)
	}
	entries, readErr := os.ReadDir(store.directory)
	if readErr != nil {
		t.Fatalf("ReadDir: %v", readErr)
	}
	expectedNames := []string{
		".rejected-20260804T120000Z-first.json",
		"20260804T120001Z-second.json",
		"20260804T120002Z-third.json",
	}
	if len(entries) != len(expectedNames) {
		t.Fatalf("remaining entries = %v, want %v", entries, expectedNames)
	}
	for index, expectedName := range expectedNames {
		if entries[index].Name() != expectedName {
			t.Fatalf("entry %d = %q, want %q", index, entries[index].Name(), expectedName)
		}
	}
}

func TestDrainQuarantinesOversizedPayloadAndContinues(t *testing.T) {
	t.Parallel()
	store, err := New(t.TempDir(), 2)
	if err != nil {
		t.Fatalf("New returned an error: %v", err)
	}
	oversizedName := "20260804T120000Z-oversized.json"
	if err := os.WriteFile(
		filepath.Join(store.directory, oversizedName),
		make([]byte, maximumPayloadBytes+1),
		0o600); err != nil {
		t.Fatalf("WriteFile oversized payload: %v", err)
	}
	if err := store.Store("20260804T120001Z-valid", []byte(`{"id":2}`)); err != nil {
		t.Fatalf("Store valid payload: %v", err)
	}
	deliveries := 0

	result, err := store.Drain(func(payload []byte) error {
		deliveries++
		if string(payload) != `{"id":2}` {
			t.Fatalf("delivered payload = %q, want valid payload", payload)
		}
		return nil
	})

	if err != nil {
		t.Fatalf("Drain returned an error: %v", err)
	}
	if result.Delivered != 1 || result.Rejected != 1 {
		t.Fatalf("Drain result = %+v, want one delivery and one rejection", result)
	}
	if deliveries != 1 {
		t.Fatalf("delivery calls = %d, want 1", deliveries)
	}
	entries, readErr := os.ReadDir(store.directory)
	if readErr != nil {
		t.Fatalf("ReadDir: %v", readErr)
	}
	if len(entries) != 1 || entries[0].Name() != ".rejected-"+oversizedName {
		t.Fatalf("remaining entries = %v, want quarantined oversized payload", entries)
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
