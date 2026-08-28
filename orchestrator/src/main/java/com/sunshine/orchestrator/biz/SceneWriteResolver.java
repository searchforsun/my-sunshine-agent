package com.sunshine.orchestrator.biz;

import com.sunshine.orchestrator.catalog.AgentCatalogService;
import com.sunshine.orchestrator.catalog.SkillCatalogService;
import com.sunshine.orchestrator.client.BizSceneCatalogClient;
import com.sunshine.orchestrator.conversation.MessageBodyText;
import com.sunshine.orchestrator.conversation.entity.ChatMessageEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 写路径场景回退链（authority §5.5）：偏好抽取前解析 biz_scene。
 * ① RoutingResult 优先（消息上落库的 routing_skill_ids/routing_agent_ids）
 * ② embedding 回退（assistant 终态正文 + user query）
 * ③ LLM 自动创建 auto 场景（防污染闸门，§5.5b）
 * 得到 scene 后作为 {@code biz_scene_scope} 注入偏好抽取。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneWriteResolver {

    private final BusinessContextProperties properties;
    private final AgentCatalogService agentCatalogService;
    private final SkillCatalogService skillCatalogService;
    private final BizSceneCatalogClient bizSceneCatalogClient;
    private final SceneEmbeddingService embeddingService;
    private final SceneAutoCreateService sceneAutoCreateService;

    public Optional<String> resolve(String userId, String tenantId, String conversationId,
                                    List<ChatMessageEntity> messages) {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        // ① 资源召回优先：路由种子（routing_skill_ids/routing_agent_ids）
        String scene = resolveFromRouting(messages);
        if (scene != null) {
            log.info("[SceneWrite] ① 路由种子命中 scene={}", scene);
            return Optional.of(scene);
        }
        // ② embedding 回退：assistant 终态正文 + user query
        if (embeddingService.enabled()) {
            String text = buildEmbeddingText(messages);
            Optional<SceneEmbeddingService.SceneMatch> match = embeddingService.search(text);
            if (match.isPresent()) {
                log.info("[SceneWrite] ② embedding 回退命中 scene={} score={}",
                        match.get().bizScene(), match.get().score());
                return Optional.of(match.get().bizScene());
            }
        }
        // ③ LLM 自动创建（含重复抑制）
        try {
            return sceneAutoCreateService.tryCreate(tenantId, conversationId,
                    lastUserBodies(messages), lastAssistantBodies(messages));
        } catch (Exception e) {
            log.warn("[SceneWrite] ③ auto-create 失败: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 路由种子解析：与读路径一致（agent 优先 → skill 第一非空 → null），仅 active 闭集有效。 */
    private String resolveFromRouting(List<ChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return null;
        }
        List<BizSceneResolver.SceneTagged> agents = new ArrayList<>();
        List<BizSceneResolver.SceneTagged> skills = new ArrayList<>();
        for (ChatMessageEntity m : messages) {
            if (m == null) {
                continue;
            }
            if (StringUtils.hasText(m.getRoutingAgentIds())) {
                for (String id : m.getRoutingAgentIds().split(",")) {
                    String aid = id.strip();
                    if (aid.isEmpty()) {
                        continue;
                    }
                    agentCatalogService.findIndex(aid)
                            .ifPresent(e -> agents.add(new BizSceneResolver.SceneTagged(e.id(), e.bizScene())));
                }
            }
            if (StringUtils.hasText(m.getRoutingSkillIds())) {
                for (String id : m.getRoutingSkillIds().split(",")) {
                    String sid = id.strip();
                    if (sid.isEmpty()) {
                        continue;
                    }
                    skillCatalogService.find(sid)
                            .ifPresent(e -> skills.add(new BizSceneResolver.SceneTagged(e.id(), e.bizScene())));
                }
            }
            if (!agents.isEmpty() || !skills.isEmpty()) {
                return BizSceneResolver.resolve(agents, skills, bizSceneCatalogClient.activeCodes())
                        .orElse(null);
            }
        }
        return null;
    }

    /** embedding 输入：assistant 终态正文（截断 500 chars）+ user query。 */
    String buildEmbeddingText(List<ChatMessageEntity> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        String assistantBody = "";
        String userQuery = "";
        for (ChatMessageEntity m : messages) {
            if (m == null) {
                continue;
            }
            String body = MessageBodyText.resolve(m);
            if ("user".equals(m.getRole()) && StringUtils.hasText(body)) {
                userQuery = body;
            }
            if ("assistant".equals(m.getRole()) && StringUtils.hasText(body)) {
                assistantBody = body;
            }
        }
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(assistantBody)) {
            sb.append(truncate(assistantBody, 500));
        }
        if (StringUtils.hasText(userQuery)) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(userQuery);
        }
        return sb.toString().strip();
    }

    private static List<String> lastUserBodies(List<ChatMessageEntity> messages) {
        List<String> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (ChatMessageEntity m : messages) {
            if (m != null && "user".equals(m.getRole()) && StringUtils.hasText(MessageBodyText.resolve(m))) {
                out.add(MessageBodyText.resolve(m));
            }
        }
        return out;
    }

    private static List<String> lastAssistantBodies(List<ChatMessageEntity> messages) {
        List<String> out = new ArrayList<>();
        if (messages == null) {
            return out;
        }
        for (ChatMessageEntity m : messages) {
            if (m != null && "assistant".equals(m.getRole()) && StringUtils.hasText(MessageBodyText.resolve(m))) {
                out.add(MessageBodyText.resolve(m));
            }
        }
        return out;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
