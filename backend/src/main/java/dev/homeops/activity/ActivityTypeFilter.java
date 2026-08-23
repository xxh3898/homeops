package dev.homeops.activity;

public enum ActivityTypeFilter {
    ALL(null),
    DEPLOYMENT("DEPLOYMENT"),
    BACKUP("BACKUP"),
    INCIDENT("INCIDENT"),
    AGENT("AGENT"),
    CONTAINER_ACTION("CONTAINER_ACTION");

    private final String databaseValue;

    ActivityTypeFilter(String databaseValue) {
        this.databaseValue = databaseValue;
    }

    public static ActivityTypeFilter fromQuery(String[] values) {
        if (values == null) {
            return ALL;
        }
        if (values.length != 1 || values[0] == null || values[0].isBlank()) {
            throw new InvalidActivityTypeException();
        }
        try {
            ActivityTypeFilter filter = valueOf(values[0]);
            if (filter == ALL) {
                throw new InvalidActivityTypeException();
            }
            return filter;
        } catch (IllegalArgumentException exception) {
            throw new InvalidActivityTypeException();
        }
    }

    String databaseValue() {
        return databaseValue;
    }
}
