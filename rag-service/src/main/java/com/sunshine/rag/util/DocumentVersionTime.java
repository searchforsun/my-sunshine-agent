package com.sunshine.rag.util;

import com.sunshine.common.util.VersionTimestampDedup;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** 文档版本号：yyyyMMddHHmmss（Asia/Shanghai） */
public final class DocumentVersionTime {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZONE);
    private static final DateTimeFormatter PARSE = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private DocumentVersionTime() {
    }

    public static String now() {
        return FMT.format(Instant.now());
    }

    public static String fromInstant(Instant instant) {
        return FMT.format(instant);
    }

    public static Instant toInstant(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version empty");
        }
        LocalDateTime ldt = LocalDateTime.parse(version.strip(), PARSE);
        return ldt.atZone(ZONE).toInstant();
    }

    /** 在已有 version key 基础上生成不重复的新 key（秒级 +1） */
    public static String uniqueKey(Collection<String> existingKeys) {
        Set<Long> occupied = new HashSet<>();
        if (existingKeys != null) {
            for (String key : existingKeys) {
                if (key == null || key.isBlank()) {
                    continue;
                }
                try {
                    occupied.add(toInstant(key.strip()).getEpochSecond());
                } catch (DateTimeParseException | IllegalArgumentException ignored) {
                    // 忽略非时间戳格式的历史 key
                }
            }
        }
        Instant unique = VersionTimestampDedup.uniqueInstant(
                Instant.now(),
                occupied.stream().map(Instant::ofEpochSecond).toList());
        return fromInstant(unique);
    }

    public static long toMilvusCode(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version empty");
        }
        return Long.parseLong(version.strip());
    }
}
