package app

import "testing"

func TestNewUUIDReturnsRFC4122Shape(t *testing.T) {
	t.Parallel()
	identifier, err := newUUID()
	if err != nil {
		t.Fatalf("newUUID returned an error: %v", err)
	}
	if len(identifier) != 36 || identifier[14] != '4' {
		t.Fatalf("identifier = %q, want version 4 UUID shape", identifier)
	}
}
