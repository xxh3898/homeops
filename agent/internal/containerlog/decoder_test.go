package containerlog

import (
	"bytes"
	"encoding/binary"
	"strings"
	"testing"
)

func TestDecodeDockerStreamAccumulatesLinesAcrossMultiplexFrames(t *testing.T) {
	t.Parallel()
	raw := append(frame(1, "2026-08-18T01:02:03Z hel"),
		frame(1, "lo\n2026-08-18T01:02:04Z second\n")...)

	output, err := DecodeDockerStream(raw, false, 50, false)

	if err != nil {
		t.Fatalf("DecodeDockerStream returned an error: %v", err)
	}
	if len(output.Lines) != 2 || output.Lines[0].Message != "hello" ||
		output.Lines[1].Message != "second" {
		t.Fatalf("lines = %#v", output.Lines)
	}
	if output.Lines[0].Stream != StreamStdout || output.Lines[0].Timestamp == nil {
		t.Fatalf("first line metadata = %#v", output.Lines[0])
	}
}

func TestDecodeDockerStreamSeparatesCRLFCRAndLF(t *testing.T) {
	t.Parallel()
	output, err := DecodeDockerStream(
		[]byte("first\r\nsecond\rthird\nfourth"), true, 50, false)
	if err != nil {
		t.Fatalf("DecodeDockerStream returned an error: %v", err)
	}
	want := []string{"first", "second", "third", "fourth"}
	if len(output.Lines) != len(want) {
		t.Fatalf("line count = %d, want %d", len(output.Lines), len(want))
	}
	for index, message := range want {
		if output.Lines[index].Message != message ||
			output.Lines[index].Stream != StreamCombined {
			t.Fatalf("line %d = %#v", index, output.Lines[index])
		}
	}
}

func TestDecodeDockerStreamDropsOverlongLogicalLine(t *testing.T) {
	t.Parallel()
	raw := []byte(strings.Repeat("x", MaximumLineBytes+1) + "\nkept\n")
	output, err := DecodeDockerStream(raw, true, 50, false)
	if err != nil {
		t.Fatalf("DecodeDockerStream returned an error: %v", err)
	}
	if !output.Truncated || len(output.Lines) != 1 || output.Lines[0].Message != "kept" {
		t.Fatalf("output = %#v", output)
	}
}

func TestDecodeDockerStreamDropsIncompleteLineWhenRawCapCutsResponse(t *testing.T) {
	t.Parallel()
	output, err := DecodeDockerStream([]byte("complete\npartial"), true, 50, true)
	if err != nil {
		t.Fatalf("DecodeDockerStream returned an error: %v", err)
	}
	if !output.Truncated || len(output.Lines) != 1 ||
		output.Lines[0].Message != "complete" {
		t.Fatalf("output = %#v", output)
	}
}

func TestDecodeDockerStreamDoesNotAllocateDeclaredFrameSize(t *testing.T) {
	t.Parallel()
	header := make([]byte, 8)
	header[0] = 1
	binary.BigEndian.PutUint32(header[4:], ^uint32(0))
	if _, err := DecodeDockerStream(append(header, 'x'), false, 50, false); err == nil {
		t.Fatal("malformed large frame was accepted")
	}
}

func TestDecodeDockerStreamBoundsFinalMessages(t *testing.T) {
	t.Parallel()
	line := strings.Repeat("x", MaximumLineBytes) + "\n"
	output, err := DecodeDockerStream(
		[]byte(strings.Repeat(line, 20)), true, 200, false)
	if err != nil {
		t.Fatalf("DecodeDockerStream returned an error: %v", err)
	}
	total := 0
	for _, item := range output.Lines {
		total += len(item.Message)
	}
	if !output.Truncated || total > MaximumMessageBytes {
		t.Fatalf("truncated/bytes = %v/%d", output.Truncated, total)
	}
}

func frame(stream byte, payload string) []byte {
	header := make([]byte, 8)
	header[0] = stream
	binary.BigEndian.PutUint32(header[4:], uint32(len(payload)))
	return append(header, []byte(payload)...)
}

func TestDecodeDockerStreamHandlesSeveralLinesInOneFrame(t *testing.T) {
	t.Parallel()
	output, err := DecodeDockerStream(
		frame(2, "first\nsecond\nthird\n"), false, 50, false)
	if err != nil {
		t.Fatalf("DecodeDockerStream returned an error: %v", err)
	}
	if len(output.Lines) != 3 || !bytes.Equal(
		[]byte(output.Lines[2].Message), []byte("third")) {
		t.Fatalf("lines = %#v", output.Lines)
	}
}

func TestDecodeDockerStreamReportsRedactionApplied(t *testing.T) {
	t.Parallel()
	output, err := DecodeDockerStream(
		[]byte("token=synthetic-token\nplain\n"), true, 50, false)
	if err != nil {
		t.Fatalf("DecodeDockerStream returned an error: %v", err)
	}
	if !output.RedactionApplied || output.Lines[0].Message != "token=[REDACTED]" {
		t.Fatalf("output = %#v", output)
	}
}
