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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/** Redis token 元数据 + 内存 Future — request_decision 同实例阻塞唤醒（独立于 HITL） */
@Slf4j
@Component
@RequiredArgsConstructor
public class DecisionRegistry {

    private static final String REDIS_KEY_PREFIX = "sunshine:decision:pending:";

    private final AgentExecutionProperties executionProperties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<String, DecisionPendingWaiter> waiters = new ConcurrentHashMap<>();
    /** messageId → token：D15 同消息唯一 awaiting 的原子占位（putIfAbsent） */
    private final ConcurrentHashMap<String, String> awaitingTokenByMessageId = new ConcurrentHashMap<>();

    public record Registration(
            String token, CompletableFuture<DecisionResult> future, long expiresAt, String title) {
    }

    public enum ResolveOutcome {
        ACCEPTED,
        INVALID_CHOICE,
        INVALID_ANSWERS,
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
        return awaitingTokenByMessageId.containsKey(messageId.strip());
    }

    /**
     * 注册待决策 token。若该 message 已有 awaiting → 抛 IllegalStateException（由 Tool 侧解释）。
     * messageId 入口 strip；D15 用 messageId→token putIfAbsent 原子占位，避免并发双注册。
     */
    public Registration register(
            String messageId,
            String userId,
            String title,
            List<DecisionQuestion> questions) {
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        String normalizedMessageId = messageId.strip();
        String token = UUID.randomUUID().toString();
        CompletableFuture<DecisionResult> future = new CompletableFuture<>();
        long expiresAt = Instant.now().plusSeconds(timeoutSec()).toEpochMilli();
        List<DecisionQuestion> frozenQuestions = questions == null ? List.of() : List.copyOf(questions);
        DecisionPendingWaiter waiter = new DecisionPendingWaiter(
                normalizedMessageId, userId, title, frozenQuestions, expiresAt, future);
        String existingToken = awaitingTokenByMessageId.putIfAbsent(normalizedMessageId, token);
        if (existingToken != null) {
            throw new IllegalStateException(
                    "decision awaiting already exists for messageId=" + normalizedMessageId);
        }
        waiters.put(token, waiter);
        storeToken(token, normalizedMessageId, userId, expiresAt, title, frozenQuestions);
        return new Registration(token, future, expiresAt, title);
    }

    public int timeoutSec() {
        return executionProperties.getReact().getDecision().getTimeoutSec();
    }

    /** 阻塞等待用户决策；超时/取消返回约定 outcome，不二次加工。 */
    public DecisionResult awaitDecision(Registration reg) throws InterruptedException {
        try {
            return reg.future().get(timeoutSec(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            cleanup(reg.token());
            return new DecisionResult("timeout", reg.title(), List.of(), System.currentTimeMillis());
        } catch (CancellationException e) {
            cleanup(reg.token());
            return new DecisionResult("cancelled", reg.title(), List.of(), System.currentTimeMillis());
        } catch (ExecutionException e) {
            cleanup(reg.token());
            throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
        } finally {
            cleanup(reg.token());
        }
    }

    public ResolveOutcome resolve(
            String token, List<DecisionAnswer> answers, String currentUserId, String expectedMessageId) {
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
        if (expectedMessageId != null && !expectedMessageId.isBlank()
                && !expectedMessageId.strip().equals(waiter.messageId())) {
            log.warn("[Decision] 拒绝 resolve：generation messageId={} vs waiter={} token={}",
                    expectedMessageId, waiter.messageId(), token);
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
        ResolveOutcome validation = validateAnswers(waiter.questions(), answers);
        if (validation != ResolveOutcome.ACCEPTED) {
            return validation;
        }
        List<DecisionAnswer> normalized = normalizeAnswers(answers);
        DecisionResult result = new DecisionResult(
                "answered", waiter.title(), normalized, System.currentTimeMillis());
        if (!waiter.future().complete(result)) {
            return ResolveOutcome.NOT_FOUND;
        }
        releaseAwaitingSlot(token, waiter);
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
            // remove(key, token)：勿裸 remove(messageId)，以免清掉并发新占位
            awaitingTokenByMessageId.remove(target, entry.getKey());
            redis.delete(redisKey(entry.getKey()));
            return true;
        });
    }

    /** 幂等清理内存 waiter + message 占位 + Redis token。 */
    public void cleanup(String token) {
        if (token == null) {
            return;
        }
        DecisionPendingWaiter removed = waiters.remove(token);
        if (removed != null) {
            awaitingTokenByMessageId.remove(removed.messageId(), token);
        }
        redis.delete(redisKey(token));
    }

    private void releaseAwaitingSlot(String token, DecisionPendingWaiter waiter) {
        waiters.remove(token, waiter);
        awaitingTokenByMessageId.remove(waiter.messageId(), token);
        redis.delete(redisKey(token));
    }

    /**
     * 答案覆盖校验：questionId 集合全等 → 每题 selected 合法 → CUSTOM 时 customInput 非空。
     */
    static ResolveOutcome validateAnswers(List<DecisionQuestion> questions, List<DecisionAnswer> answers) {
        List<DecisionQuestion> qs = questions == null ? List.of() : questions;
        if (answers == null) {
            return ResolveOutcome.INVALID_ANSWERS;
        }
        Set<String> expectedIds = qs.stream().map(DecisionQuestion::id).collect(Collectors.toSet());
        Set<String> answerIds = new HashSet<>();
        for (DecisionAnswer answer : answers) {
            if (answer == null || answer.questionId() == null || answer.questionId().isBlank()) {
                return ResolveOutcome.INVALID_ANSWERS;
            }
            if (!answerIds.add(answer.questionId())) {
                return ResolveOutcome.INVALID_ANSWERS;
            }
        }
        if (!expectedIds.equals(answerIds)) {
            return ResolveOutcome.INVALID_ANSWERS;
        }
        Map<String, DecisionQuestion> byId = qs.stream()
                .collect(Collectors.toMap(DecisionQuestion::id, q -> q, (a, b) -> a, LinkedHashMap::new));
        for (DecisionAnswer answer : answers) {
            DecisionQuestion question = byId.get(answer.questionId());
            ResolveOutcome perQuestion = validateAnswerForQuestion(question, answer);
            if (perQuestion != ResolveOutcome.ACCEPTED) {
                return perQuestion;
            }
        }
        return ResolveOutcome.ACCEPTED;
    }

    private static ResolveOutcome validateAnswerForQuestion(DecisionQuestion question, DecisionAnswer answer) {
        List<String> selected = answer.selectedOptionIds();
        if (selected == null || selected.isEmpty()) {
            return ResolveOutcome.INVALID_CHOICE;
        }
        if (!question.allowMultiple() && selected.size() != 1) {
            return ResolveOutcome.INVALID_CHOICE;
        }
        Set<String> allowed = question.options() == null
                ? Set.of(DecisionOption.CUSTOM_ID)
                : question.options().stream().map(DecisionOption::id).collect(Collectors.toSet());
        allowed = new HashSet<>(allowed);
        allowed.add(DecisionOption.CUSTOM_ID);
        for (String optionId : selected) {
            if (optionId == null || optionId.isBlank() || !allowed.contains(optionId)) {
                return ResolveOutcome.INVALID_CHOICE;
            }
        }
        if (selected.contains(DecisionOption.CUSTOM_ID) && isBlank(answer.customInput())) {
            return ResolveOutcome.INPUT_REQUIRED;
        }
        return ResolveOutcome.ACCEPTED;
    }

    private static List<DecisionAnswer> normalizeAnswers(List<DecisionAnswer> answers) {
        return answers.stream()
                .map(a -> new DecisionAnswer(
                        a.questionId(),
                        List.copyOf(a.selectedOptionIds()),
                        isBlank(a.customInput()) ? null : a.customInput().strip()))
                .toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void storeToken(
            String token,
            String messageId,
            String userId,
            long expiresAt,
            String title,
            List<DecisionQuestion> questions) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        payload.put("userId", userId != null ? userId : "");
        payload.put("expiresAt", expiresAt);
        payload.put("title", title != null ? title : "");
        payload.put("questions", questions);
        try {
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
