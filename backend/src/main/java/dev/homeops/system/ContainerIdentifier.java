package dev.homeops.system;

import java.util.regex.Pattern;

public record ContainerIdentifier(String value) {

    private static final Pattern PATTERN = Pattern.compile("^[0-9a-f]{12}$");

    public ContainerIdentifier {
        if (value == null || !PATTERN.matcher(value).matches()) {
            throw new InvalidContainerIdentifierException();
        }
    }

    public static ContainerIdentifier parse(String value) {
        return new ContainerIdentifier(value);
    }

    public boolean matches(String fullIdentifier) {
        return fullIdentifier != null && fullIdentifier.startsWith(value);
    }
}
