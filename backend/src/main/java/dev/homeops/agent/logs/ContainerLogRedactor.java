package dev.homeops.agent.logs;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ContainerLogRedactor {

    public static final String REPLACEMENT = "[REDACTED]";

    private static final Pattern HEADER_PATTERN = Pattern.compile(
            "(?i)([\"']?\\b(?:password|passwd|secret|token|access_token|refresh_token|api_key|apikey|authorization|cookie|set-cookie|credential|private_key)\\b[\"']?\\s*:\\s*)[^\\r\\n]+");
    private static final Pattern KEY_VALUE_PATTERN = Pattern.compile(
            "(?i)([\"']?\\b(?:password|passwd|secret|token|access_token|refresh_token|api_key|apikey|authorization|cookie|set-cookie|credential|private_key)\\b[\"']?\\s*(?:=|:)\\s*)(?:\"[^\"\\r\\n]*\"|'[^'\\r\\n]*'|[^\\s,;}&]+)");
    private static final Pattern AUTHORIZATION_PATTERN = Pattern.compile(
            "(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b");

    public String sanitize(String input) {
        String normalized = removeControls(stripAnsi(input));
        String redacted = HEADER_PATTERN.matcher(normalized)
                .replaceAll("$1" + REPLACEMENT);
        redacted = KEY_VALUE_PATTERN.matcher(redacted)
                .replaceAll("$1" + REPLACEMENT);
        redacted = AUTHORIZATION_PATTERN.matcher(redacted)
                .replaceAll("$1 " + REPLACEMENT);
        return JWT_PATTERN.matcher(redacted).replaceAll(REPLACEMENT);
    }

    private static String removeControls(String input) {
        StringBuilder result = new StringBuilder(input.length());
        input.codePoints().forEach(codePoint -> {
            if (codePoint == '\t'
                    || (codePoint >= 0x20 && codePoint < 0x7f)
                    || (codePoint > 0x9f
                    && !(codePoint >= 0x202a && codePoint <= 0x202e)
                    && !(codePoint >= 0x2066 && codePoint <= 0x2069))) {
                result.appendCodePoint(codePoint);
            }
        });
        return result.toString();
    }

    private static String stripAnsi(String input) {
        StringBuilder result = new StringBuilder(input.length());
        int index = 0;
        while (index < input.length()) {
            char current = input.charAt(index);
            if (current != 0x1b) {
                result.append(current);
                index++;
                continue;
            }
            index++;
            if (index >= input.length()) {
                break;
            }
            char kind = input.charAt(index++);
            if (kind == '[') {
                while (index < input.length()) {
                    char character = input.charAt(index++);
                    if (character >= 0x40 && character <= 0x7e) {
                        break;
                    }
                }
            } else if (kind == ']') {
                while (index < input.length()) {
                    char character = input.charAt(index++);
                    if (character == 0x07) {
                        break;
                    }
                    if (character == 0x1b && index < input.length()
                            && input.charAt(index) == '\\') {
                        index++;
                        break;
                    }
                }
            }
        }
        return result.toString();
    }
}
