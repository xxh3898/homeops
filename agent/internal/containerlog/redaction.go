package containerlog

import (
	"regexp"
	"strings"
	"unicode/utf8"
)

const Replacement = "[REDACTED]"

var (
	headerPattern = regexp.MustCompile(
		`(?i)(["']?\b(?:password|passwd|secret|token|access_token|refresh_token|api_key|apikey|authorization|cookie|set-cookie|credential|private_key)\b["']?\s*:\s*)[^\r\n]+`)
	keyValuePattern = regexp.MustCompile(
		`(?i)(["']?\b(?:password|passwd|secret|token|access_token|refresh_token|api_key|apikey|authorization|cookie|set-cookie|credential|private_key)\b["']?\s*(?:=|:)\s*)(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;}&]+)`)
	authorizationPattern = regexp.MustCompile(
		`(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]+`)
	jwtPattern = regexp.MustCompile(
		`\b[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b`)
)

func NormalizeAndRedact(input []byte) string {
	withoutANSI := stripANSI(input)
	valid := strings.ToValidUTF8(string(withoutANSI), string(utf8.RuneError))
	var normalized strings.Builder
	normalized.Grow(len(valid))
	for _, character := range valid {
		if allowedCharacter(character) {
			normalized.WriteRune(character)
		}
	}
	value := headerPattern.ReplaceAllString(normalized.String(), `${1}`+Replacement)
	value = keyValuePattern.ReplaceAllString(value, `${1}`+Replacement)
	value = authorizationPattern.ReplaceAllString(value, `${1} `+Replacement)
	return jwtPattern.ReplaceAllString(value, Replacement)
}

func allowedCharacter(character rune) bool {
	if character == '\t' {
		return true
	}
	if character < 0x20 || (character >= 0x7f && character <= 0x9f) {
		return false
	}
	if (character >= 0x202a && character <= 0x202e) ||
		(character >= 0x2066 && character <= 0x2069) {
		return false
	}
	return true
}

func stripANSI(input []byte) []byte {
	result := make([]byte, 0, len(input))
	for index := 0; index < len(input); {
		if input[index] != 0x1b {
			result = append(result, input[index])
			index++
			continue
		}
		index++
		if index >= len(input) {
			break
		}
		switch input[index] {
		case '[':
			index++
			for index < len(input) {
				character := input[index]
				index++
				if character >= 0x40 && character <= 0x7e {
					break
				}
			}
		case ']':
			index++
			for index < len(input) {
				if input[index] == 0x07 {
					index++
					break
				}
				if input[index] == 0x1b && index+1 < len(input) &&
					input[index+1] == '\\' {
					index += 2
					break
				}
				index++
			}
		default:
			index++
		}
	}
	return result
}
