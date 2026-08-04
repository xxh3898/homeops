package spool

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

const maximumPayloadBytes = 512 * 1024

var ErrFull = errors.New("Agent spool is full")

type Spool struct {
	directory string
	maximum   int
}

func New(directory string, maximum int) (*Spool, error) {
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return nil, fmt.Errorf("create Agent spool: %w", err)
	}
	if err := os.Chmod(directory, 0o700); err != nil {
		return nil, fmt.Errorf("protect Agent spool: %w", err)
	}
	return &Spool{directory: directory, maximum: maximum}, nil
}

func (spool *Spool) Store(name string, payload []byte) error {
	if len(payload) > maximumPayloadBytes {
		return errors.New("snapshot exceeds spool payload limit")
	}
	if !safeName(name) {
		return errors.New("snapshot spool name is invalid")
	}
	entryCount, err := spool.payloadEntryCount()
	if err != nil {
		return err
	}
	if entryCount >= spool.maximum {
		return ErrFull
	}
	temporary, err := os.CreateTemp(spool.directory, ".pending-*.json")
	if err != nil {
		return fmt.Errorf("create spool temporary file: %w", err)
	}
	temporaryName := temporary.Name()
	committed := false
	defer func() {
		_ = temporary.Close()
		if !committed {
			_ = os.Remove(temporaryName)
		}
	}()
	if err := temporary.Chmod(0o600); err != nil {
		return fmt.Errorf("protect spool temporary file: %w", err)
	}
	if _, err := temporary.Write(payload); err != nil {
		return fmt.Errorf("write spool temporary file: %w", err)
	}
	if err := temporary.Sync(); err != nil {
		return fmt.Errorf("sync spool temporary file: %w", err)
	}
	if err := temporary.Close(); err != nil {
		return fmt.Errorf("close spool temporary file: %w", err)
	}
	target := filepath.Join(spool.directory, name+".json")
	if err := os.Rename(temporaryName, target); err != nil {
		return fmt.Errorf("commit spool file: %w", err)
	}
	committed = true
	return nil
}

func (spool *Spool) Drain(send func([]byte) error) error {
	files, err := spool.files()
	if err != nil {
		return err
	}
	for _, file := range files {
		path := filepath.Join(spool.directory, file)
		payload, readErr := os.ReadFile(path)
		if readErr != nil {
			return fmt.Errorf("read spool file: %w", readErr)
		}
		if len(payload) > maximumPayloadBytes {
			return errors.New("stored snapshot exceeds spool payload limit")
		}
		if sendErr := send(payload); sendErr != nil {
			var permanent interface{ Permanent() bool }
			if errors.As(sendErr, &permanent) && permanent.Permanent() {
				rejectedPath := filepath.Join(
					spool.directory,
					".rejected-"+file)
				if renameErr := os.Rename(path, rejectedPath); renameErr != nil {
					return fmt.Errorf(
						"quarantine rejected spool file: %w",
						renameErr)
				}
				return fmt.Errorf(
					"snapshot was permanently rejected and quarantined: %w",
					sendErr)
			}
			return sendErr
		}
		if removeErr := os.Remove(path); removeErr != nil {
			return fmt.Errorf("remove delivered spool file: %w", removeErr)
		}
	}
	return nil
}

func (spool *Spool) payloadEntryCount() (int, error) {
	entries, err := os.ReadDir(spool.directory)
	if err != nil {
		return 0, fmt.Errorf("read Agent spool: %w", err)
	}
	count := 0
	for _, entry := range entries {
		if entry.Type().IsRegular() && strings.HasSuffix(entry.Name(), ".json") {
			count++
		}
	}
	return count, nil
}

func (spool *Spool) files() ([]string, error) {
	entries, err := os.ReadDir(spool.directory)
	if err != nil {
		return nil, fmt.Errorf("read Agent spool: %w", err)
	}
	files := make([]string, 0, len(entries))
	for _, entry := range entries {
		if entry.Type().IsRegular() &&
			!strings.HasPrefix(entry.Name(), ".") &&
			strings.HasSuffix(entry.Name(), ".json") {
			files = append(files, entry.Name())
		}
	}
	sort.Strings(files)
	return files, nil
}

func safeName(value string) bool {
	if len(value) < 10 || len(value) > 128 {
		return false
	}
	for _, char := range value {
		if (char >= 'a' && char <= 'z') ||
			(char >= 'A' && char <= 'Z') ||
			(char >= '0' && char <= '9') ||
			char == '-' ||
			char == '_' {
			continue
		}
		return false
	}
	return true
}
