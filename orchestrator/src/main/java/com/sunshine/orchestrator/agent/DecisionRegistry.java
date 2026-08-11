package com.sunshine.orchestrator.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sunshine.orchestrator.config.AgentExecutionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Redis token 元数据 + 内存 Future — request_decision 同实例阻塞唤醒（独立于 HITL） */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionRegistry {

    private static final String REDIS_KEY_PREFIX = "sunshine:decision:pending:";
    private static final String CUSTOM_CHOICE = "__custom__";

    private final AgentExecutionProperties executionProperties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, DecisionPendingWaiter> waiters = new ConcurrentHashMap<>();

    public record Registration(String token, CompletableFuture<DecisionResult> future, long expiresAt) {
    }

    public enum ResolveOutcome {
        ACCEPTED,
        INVALID_CHOICE,
        INPUT_REQUIRED,
        EXPIRED,
        NOT_FOUND,
        FORBIDDEN
    }

    /** 同一 messageId 是否仍有未完成的 awaiting decision（D15） */
    public boolean hasAwaiting(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }
        String target = messageId.strip();
        return waiters.values().stream()
                .anyMatch(w -> target.equals(w.messageId()) && !w.future().isDone());
    }

    /**
     * 注册待决策 token。若该 message 已有 awaiting → 抛 IllegalStateException（由 Tool 侧解释）。
     * messageId 入口 strip，与 hasAwaiting / cancelWaitersForMessage 对齐（D15）。
     */
    public Registration register(
            String messageId,
            String userId,
            String question,
            List<DecisionOption> options,
            boolean allowCustomInput) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        String normalizedMessageId = messageId.strip();
        if (hasAwaiting(normalizedMessageId)) {
            throw new IllegalStateException(
                    "decision awaiting already exists for messageId=" + normalizedMessageId);
        }
        String token = UUID.randomUUID().toString();
        CompletableFuture<DecisionResult> future = new CompletableFuture<>();
        long expiresAt = Instant.now().plusSeconds(timeoutSec()).toEpochMilli();
        waiters.put(token, new DecisionPendingWaiter(
                normalizedMessageId, userId, question, List.copyOf(options), allowCustomInput, expiresAt, future));
        storeToken(token, normalizedMessageId, userId, expiresAt, question, options, allowCustomInput);
        return new Registration(token, future, expiresAt);
    }

    public int timeoutSec() {
        return executionProperties.getReact().getDecision().getTimeoutSec();
    }

    /** 阻塞等待用户决策；超时/取消返回约定 choice，不二次加工。 */
    public DecisionResult awaitDecision(Registration reg) throws InterruptedException {
        try {
            return reg.future().get(timeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cleanup(reg.token());
            return new DecisionResult("__timeout__", null, System.currentTimeMillis());
        } catch (CancellationException e) {
            cleanup(reg.token());
            return new DecisionResult("__cancelled__", null, System.currentTimeMillis());
        } catch (ExecutionException e) {
            cleanup(reg.token());
            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
        } finally {
            cleanup(reg.token());
        }
    }

    /** 校验 choice/customInput；失败不 complete Future。 */
    public ResolveOutcome resolve(String token, String choice, String customInput, String currentUserId) {
        if (token == null || token.isBlank()) {
            return ResolveOutcome.NOT_FOUND;
        }
        DecisionPendingWaiter waiter = waiters.get(token);
        if (waiter == null) {
            String key = redisKey(token);
            if (!Boolean.TRUE.equals(redis.hasKey(key))) {
                log.warn("[Decision] resolve 无效 token={}", token);
                return ResolveOutcome.NOT_FOUND;
            }
            redis.delete(key);
            log.warn("[Decision] resolve token={} 无本地 waiter（可能已超时或其它实例）", token);
            return ResolveOutcome.NOT_FOUND;
        }
        if (waiter.future().isDone()) {
            return ResolveOutcome.NOT_FOUND;
        }
        String userId = waiter.userId();
        if (userId != null && !userId.isBlank()
                && currentUserId != null
                && !userId.equals(currentUserId)) {
            log.warn("[Decision] 拒绝 resolve：发起用户 {} vs 当前用户 {} token={}",
                    userId, currentUserId, token);
            return ResolveOutcome.FORBIDDEN;
        }
        if (System.currentTimeMillis() > waiter.expiresAt()) {
            cleanup(token);
            return ResolveOutcome.EXPIRED;
        }
        if (!isValidChoice(choice, waiter.options(), waiter.allowCustomInput())) {
            return ResolveOutcome.INVALID_CHOICE;
        }
        if (requiresCustomInput(choice, waiter.options()) && isBlank(customInput)) {
            return ResolveOutcome.INPUT_REQUIRED;
        }
        String normalizedInput = isBlank(customInput) ? null : customInput.strip();
        DecisionResult result = new DecisionResult(choice, normalizedInput, System.currentTimeMillis());
        if (!waiter.future().complete(result)) {
            return ResolveOutcome.NOT_FOUND;
        }
        waiters.remove(token, waiter);
        redis.delete(redisKey(token));
        return ResolveOutcome.ACCEPTED;
    }

    public void cancelWaitersForMessage(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        String target = messageId.strip();
        waiters.entrySet().removeIf(entry -> {
            DecisionPendingWaiter waiter = entry.getValue();
            if (!target.equals(waiter.messageId())) {
                return false;
            }
            waiter.future().cancel(true);
            redis.delete(redisKey(entry.getKey()));
            return true;
        });
    }

    /** 幂等清理内存 waiter + Redis token。 */
    public void cleanup(String token) {
        if (token != null) {
            waiters.remove(token);
            redis.delete(redisKey(token));
        }
    }

    private static boolean isValidChoice(String choice, List<DecisionOption> options, boolean allowCustomInput) {
        if (choice == null || choice.isBlank()) {
            return false;
        }
        if (CUSTOM_CHOICE.equals(choice)) {
            return allowCustomInput;
        }
        return options.stream().anyMatch(o -> choice.equals(o.value()));
    }

    private static boolean requiresCustomInput(String choice, List<DecisionOption> options) {
        if (CUSTOM_CHOICE.equals(choice)) {
            return true;
        }
        return options.stream()
                .filter(o -> choice.equals(o.value()))
                .findFirst()
                .map(DecisionOption::requireInput)
                .orElse(false);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void storeToken(
            String token,
            String messageId,
            String userId,
            long expiresAt,
            String question,
            List<DecisionOption> options,
            boolean allowCustomInput) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("userId", userId != null ? userId : "");
        payload.put("expiresAt", String.valueOf(expiresAt));
        payload.put("question", question != null ? question : "");
        payload.put("allowCustomInput", String.valueOf(allowCustomInput));
        try {
            payload.put("optionsJson", objectMapper.writeValueAsString(options));
            redis.opsForValue().set(
                    redisKey(token),
                    objectMapper.writeValueAsString(payload),
                    Duration.ofSeconds(timeoutSec() + 30));
        } catch (JsonProcessingException e) {
            log.warn("[Decision] token 序列化失败: {}", e.getMessage());
        }
    }

    private static String redisKey(String token) {
        return REDIS_KEY_PREFIX + token;
    }
}
