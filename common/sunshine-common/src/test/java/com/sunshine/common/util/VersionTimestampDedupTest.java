package com.sunshine.common.util;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class VersionTimestampDedupTest {

    @Test
    void bumpsWhenSameSecondOccupied() {
        Instant base = Instant.parse("2026-07-03T14:22:03.456Z");
        Instant result = VersionTimestampDedup.uniqueInstant(base, List.of(base));
        assertThat(result.getEpochSecond()).isEqualTo(base.getEpochSecond() + 1);
    }

    @Test
    void keepsWhenSecondFree() {
        Instant base = Instant.parse("2026-07-03T14:22:03.456Z");
        Instant other = Instant.parse("2026-07-03T14:22:04.100Z");
        Instant result = VersionTimestampDedup.uniqueInstant(base, List.of(other));
        assertThat(result).isEqualTo(base);
    }

    @Test
    void bumpsMultipleTimesUntilFree() {
        Instant base = Instant.parse("2026-07-03T14:22:03Z");
        Instant plus1 = base.plusSeconds(1);
        Instant result = VersionTimestampDedup.uniqueInstant(base, List.of(base, plus1));
        assertThat(result.getEpochSecond()).isEqualTo(base.getEpochSecond() + 2);
    }
}
