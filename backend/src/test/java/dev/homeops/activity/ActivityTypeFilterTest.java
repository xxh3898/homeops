package dev.homeops.activity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActivityTypeFilterTest {
    @Test
    void should_useAllScope_when_queryParameterIsOmitted() {
        assertThat(ActivityTypeFilter.fromQuery(null)).isEqualTo(ActivityTypeFilter.ALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DEPLOYMENT", "BACKUP", "INCIDENT", "AGENT", "CONTAINER_ACTION"})
    void should_acceptExactSupportedType(String value) {
        assertThat(ActivityTypeFilter.fromQuery(new String[] {value})).isEqualTo(ActivityTypeFilter.valueOf(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "deployment", "UNKNOWN", "ALL"})
    void should_rejectUnsupportedOrExplicitlyUnfilteredValue(String value) {
        assertThatThrownBy(() -> ActivityTypeFilter.fromQuery(new String[] {value}))
                .isInstanceOf(InvalidActivityTypeException.class);
    }

    @Test
    void should_rejectMultipleValues() {
        assertThatThrownBy(() -> ActivityTypeFilter.fromQuery(new String[] {"DEPLOYMENT", "BACKUP"}))
                .isInstanceOf(InvalidActivityTypeException.class);
    }
}
