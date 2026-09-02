package com.sunshine.orchestrator.agent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sunshine.orchestrator.agent.runtime.AgentRunRequest;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;

/**
 * HarnessAgent 配置指纹缓存（E5，P2-1）：key = {@link HarnessAgentFactory#fingerprint(AgentRunRequest)}。
 *
 * <p>官方无状态模型下 agent 实例仅承载不可变构建配置（sysPrompt/toolkit/maxIters/压缩等），
 * per-call 状态走 RuntimeContext。配置相同即等价复用；配置变化（React Catalog refresh、
 * skill 绑定、工具集变更）→ 指纹变化 → 自然新建。不 mutate 存活实例的 toolkit——
 * 在飞 call 与新建 call 会看到不一致 schema，比重建更危险。
 *
 * <p>有界（64 条）+ expireAfterAccess（2h）防配置空间膨胀；catalogVersion 含于指纹，
 * ToolCatalogService.refresh 后旧指纹条目不再命中，过期即回收。
 */
@Component
public class HarnessAgentHolder {

    private final Cache<String, HarnessAgent> cache = Caffeine.newBuilder()
            .maximumSize(64)
            .expireAfterAccess(Duration.ofHours(2))
            .build();

    private final HarnessAgentFactory factory;

    public HarnessAgentHolder(HarnessAgentFactory factory) {
        this.factory = factory;
    }

    public HarnessAgent get(AgentRunRequest request) {
        return cache.get(factory.fingerprint(request), k -> factory.create(request));
    }

    /** 优雅停机用：遍历全部缓存实例（HarnessAgentShutdownHook） */
    public Collection<HarnessAgent> getAll() {
        return cache.asMap().values();
    }
}
