package containerlog

import (
	"bytes"
	"encoding/binary"
	"errors"
	"io"
	"time"
)

func DecodeDockerStream(
	raw []byte,
	tty bool,
	tail int,
	rawTruncated bool,
) (Output, error) {
	if !AllowedTail(tail) {
		return Output{}, errors.New("log tail is invalid")
	}
	collector := &lineCollector{maximumLines: tail}
	if tty {
		accumulator := newLineAccumulator(StreamCombined, collector)
		accumulator.Write(raw)
		accumulator.Finish(!rawTruncated)
		if rawTruncated {
			collector.truncated = true
		}
		return collector.Output(), nil
	}

	accumulators := map[Stream]*lineAccumulator{
		StreamStdout: newLineAccumulator(StreamStdout, collector),
		StreamStderr: newLineAccumulator(StreamStderr, collector),
	}
	reader := bytes.NewReader(raw)
	for reader.Len() > 0 {
		var header [8]byte
		if _, err := io.ReadFull(reader, header[:]); err != nil {
			if rawTruncated {
				collector.truncated = true
				finishAccumulators(accumulators, false)
				return collector.Output(), nil
			}
			return Output{}, errors.New("Docker log stream header is invalid")
		}
		if header[1] != 0 || header[2] != 0 || header[3] != 0 {
			return Output{}, errors.New("Docker log stream header is invalid")
		}
		var stream Stream
		switch header[0] {
		case 1:
			stream = StreamStdout
		case 2:
			stream = StreamStderr
		default:
			return Output{}, errors.New("Docker log stream type is invalid")
		}
		remaining := int64(binary.BigEndian.Uint32(header[4:]))
		var chunk [4096]byte
		for remaining > 0 {
			wanted := int64(len(chunk))
			if remaining < wanted {
				wanted = remaining
			}
			read, err := io.ReadFull(reader, chunk[:wanted])
			if read > 0 {
				accumulators[stream].Write(chunk[:read])
				remaining -= int64(read)
			}
			if err != nil {
				if rawTruncated {
					collector.truncated = true
					finishAccumulators(accumulators, false)
					return collector.Output(), nil
				}
				return Output{}, errors.New("Docker log stream frame is incomplete")
			}
		}
	}
	finishAccumulators(accumulators, !rawTruncated)
	if rawTruncated {
		collector.truncated = true
	}
	return collector.Output(), nil
}

func finishAccumulators(
	accumulators map[Stream]*lineAccumulator,
	allowPartial bool,
) {
	accumulators[StreamStdout].Finish(allowPartial)
	accumulators[StreamStderr].Finish(allowPartial)
}

type lineAccumulator struct {
	stream     Stream
	collector  *lineCollector
	buffer     []byte
	overlong   bool
	skipLineLF bool
}

func newLineAccumulator(
	stream Stream,
	collector *lineCollector,
) *lineAccumulator {
	return &lineAccumulator{
		stream:    stream,
		collector: collector,
		buffer:    make([]byte, 0, 256),
	}
}

func (accumulator *lineAccumulator) Write(chunk []byte) {
	for _, character := range chunk {
		if accumulator.skipLineLF {
			accumulator.skipLineLF = false
			if character == '\n' {
				continue
			}
		}
		switch character {
		case '\r':
			accumulator.complete()
			accumulator.skipLineLF = true
		case '\n':
			accumulator.complete()
		default:
			if accumulator.overlong {
				continue
			}
			if len(accumulator.buffer) == MaximumLineBytes {
				accumulator.buffer = accumulator.buffer[:0]
				accumulator.overlong = true
				accumulator.collector.truncated = true
				continue
			}
			accumulator.buffer = append(accumulator.buffer, character)
		}
	}
}

func (accumulator *lineAccumulator) Finish(allowPartial bool) {
	if !allowPartial {
		if len(accumulator.buffer) > 0 || accumulator.overlong {
			accumulator.collector.truncated = true
		}
		accumulator.reset()
		return
	}
	if len(accumulator.buffer) > 0 || accumulator.overlong {
		accumulator.complete()
	}
}

func (accumulator *lineAccumulator) complete() {
	if !accumulator.overlong {
		accumulator.collector.Add(accumulator.stream, accumulator.buffer)
	}
	accumulator.reset()
}

func (accumulator *lineAccumulator) reset() {
	accumulator.buffer = accumulator.buffer[:0]
	accumulator.overlong = false
}

type lineCollector struct {
	maximumLines     int
	messageBytes     int
	lines            []Line
	truncated        bool
	redactionApplied bool
}

func (collector *lineCollector) Add(stream Stream, raw []byte) {
	if len(collector.lines) >= collector.maximumLines {
		collector.truncated = true
		return
	}
	timestamp, message := splitTimestamp(raw)
	normalized, redactionApplied := NormalizeAndRedact(message)
	collector.redactionApplied = collector.redactionApplied || redactionApplied
	if collector.messageBytes+len(normalized) > MaximumMessageBytes {
		collector.truncated = true
		return
	}
	collector.messageBytes += len(normalized)
	collector.lines = append(collector.lines, Line{
		Timestamp: timestamp,
		Stream:    stream,
		Message:   normalized,
	})
}

func (collector *lineCollector) Output() Output {
	lines := collector.lines
	if lines == nil {
		lines = []Line{}
	}
	return Output{
		Lines:            lines,
		Truncated:        collector.truncated,
		RedactionApplied: collector.redactionApplied,
	}
}

func splitTimestamp(raw []byte) (*time.Time, []byte) {
	separator := bytes.IndexByte(raw, ' ')
	if separator <= 0 {
		return nil, raw
	}
	parsed, err := time.Parse(time.RFC3339Nano, string(raw[:separator]))
	if err != nil {
		return nil, raw
	}
	utc := parsed.UTC()
	return &utc, raw[separator+1:]
}
