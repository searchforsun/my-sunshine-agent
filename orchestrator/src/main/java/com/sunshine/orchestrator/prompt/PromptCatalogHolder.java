package com.sunshine.orchestrator.prompt;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Catalog Snapshot 持有者：启动后 fail-fast；定时刷新失败保留旧视图。
 */
@Slf4j
@Component
public class PromptCatalogHolder {

    private final AtomicReference<PromptCatalogSnapshot> ref = new AtomicReference<>();

    public void replace(PromptCatalogSnapshot snapshot) {
        ref.set(Objects.requireNonNull(snapshot, "snapshot"));
    }

    /** 启动完成后调用；未加载则抛 {@link IllegalStateException} */
    public PromptCatalogSnapshot snapshot() {
        PromptCatalogSnapshot current = ref.get();
        if (current == null) {
            throw new IllegalStateException("PromptCatalogSnapshot not loaded");
        }
        return current;
    }

    public Optional<PromptCatalogEntry> entry(String id) {
        return snapshot().entry(id);
    }

    /** Catalog 正文；缺 id 或空 → 空串 + warn（禁止 Nacos 影子兜底） */
    public String requireText(String id) {
        return snapshot().text(id).orElseGet(() -> {
            log.warn("[PromptCatalog] missing text id={}", id);
            return "";
        });
    }

    /**
     * 安全刷新：loader 成功且 catalogVersion 变化时 replace；失败保留旧 snapshot。
     *
     * @return 是否发生替换
     */
    public boolean refreshSafely(Supplier<PromptCatalogSnapshot> loader) {
        try {
            PromptCatalogSnapshot next = Objects.requireNonNull(loader.get(), "snapshot");
            PromptCatalogSnapshot prev = ref.get();
            if (prev != null && prev.catalogVersion() == next.catalogVersion()) {
                return false;
            }
            ref.set(next);
            return true;
        } catch (Exception e) {
            log.warn("[PromptCatalog] refresh failed, keep previous: {}", e.getMessage());
            return false;
        }
    }
}
