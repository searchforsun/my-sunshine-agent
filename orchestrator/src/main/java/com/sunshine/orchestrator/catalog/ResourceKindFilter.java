package com.sunshine.orchestrator.catalog;

import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * 会话 kind 资源召回过滤：保留 {@code resource.kind == all || resource.kind == sessionKind}。
 * kind ⊥ executionMode ⊥ biz_scene ⊥ callSite。
 */
public final class ResourceKindFilter {

    public interface Kinded {
        String id();

        String kind();
    }

    private ResourceKindFilter() {
    }

    /** 会话形态缺省 chat；资源 kind 缺省 all。 */
    public static boolean matches(String resourceKind, String sessionKind) {
        String rk = normalizeResourceKind(resourceKind);
        String sk = normalizeSessionKind(sessionKind);
        return "all".equals(rk) || rk.equals(sk);
    }

    public static <T extends Kinded> List<T> retain(List<T> entries, String sessionKind) {
        return retain(entries, sessionKind, Kinded::kind);
    }

    public static <T> List<T> retain(List<T> entries, String sessionKind, Function<T, String> kindAccessor) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        Objects.requireNonNull(kindAccessor, "kindAccessor");
        return entries.stream()
                .filter(e -> matches(kindAccessor.apply(e), sessionKind))
                .toList();
    }

    static String normalizeSessionKind(String sessionKind) {
        return StringUtils.hasText(sessionKind) ? sessionKind.strip() : "chat";
    }

    static String normalizeResourceKind(String resourceKind) {
        return StringUtils.hasText(resourceKind) ? resourceKind.strip() : "all";
    }
}
