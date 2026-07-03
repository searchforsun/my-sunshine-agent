package com.sunshine.common.util;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** 版本时间戳秒级去重：同一秒内已有版本则向后 +1s 直到唯一 */
public final class VersionTimestampDedup {

    private VersionTimestampDedup() {
    }

    public static Instant uniqueInstant(Instant candidate, Iterable<Instant> existing) {
        Instant next = candidate != null ? candidate : Instant.now();
        Set<Long> occupied = new HashSet<>();
        if (existing != null) {
            for (Instant instant : existing) {
                if (instant != null) {
                    occupied.add(instant.getEpochSecond());
                }
            }
        }
        while (occupied.contains(next.getEpochSecond())) {
            next = next.plusSeconds(1);
        }
        return next;
    }
}
