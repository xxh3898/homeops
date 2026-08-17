package containerlog

import (
	"encoding/json"
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func TestNormalizeAndRedactMatchesSharedContract(t *testing.T) {
	t.Parallel()
	_, source, _, ok := runtime.Caller(0)
	if !ok {
		t.Fatal("locate redaction test source")
	}
	path := filepath.Join(
		filepath.Dir(source),
		"..", "..", "..", "contracts", "container-log-redaction-v1.json")
	contents, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read shared redaction contract: %v", err)
	}
	var fixture struct {
		Version int `json:"version"`
		Vectors []struct {
			Name     string `json:"name"`
			Input    string `json:"input"`
			Expected string `json:"expected"`
		} `json:"vectors"`
	}
	if err := json.Unmarshal(contents, &fixture); err != nil {
		t.Fatalf("decode shared redaction contract: %v", err)
	}
	if fixture.Version != 1 || len(fixture.Vectors) == 0 {
		t.Fatalf("fixture version/vectors = %d/%d", fixture.Version, len(fixture.Vectors))
	}
	for _, vector := range fixture.Vectors {
		vector := vector
		t.Run(vector.Name, func(t *testing.T) {
			t.Parallel()
			if actual := NormalizeAndRedact([]byte(vector.Input)); actual != vector.Expected {
				t.Fatalf("NormalizeAndRedact() = %q, want %q", actual, vector.Expected)
			}
		})
	}
}

func TestNormalizeAndRedactReplacesInvalidUTF8AndRemovesControls(t *testing.T) {
	t.Parallel()
	input := []byte{'o', 'k', 0xff, 0x00, '\t', 'x'}
	if actual := NormalizeAndRedact(input); actual != "ok�\tx" {
		t.Fatalf("NormalizeAndRedact() = %q", actual)
	}
}
