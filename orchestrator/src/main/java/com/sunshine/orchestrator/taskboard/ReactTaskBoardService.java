package com.sunshine.orchestrator.taskboard;

import com.sunshine.orchestrator.config.AgentExecutionProperties;
import com.sunshine.orchestrator.processing.ProcessingTimelineSession;
import com.sunshine.orchestrator.processing.TimelineStepId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TaskBoard 会话态 CRUD 与校验 */
@Service
@RequiredArgsConstructor
public class ReactTaskBoardService {

    private static final Set<String> ALLOWED_STATUS = Set.of(
            "pending", "in_progress", "completed", "cancelled");
    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "edges", "tool", "nodeType", "skillId", "dependsOn");
    private static final Pattern TASK_ID = Pattern.compile("^t(\\d+)$");

    private final ReactTaskBoardStore store;
    private final AgentExecutionProperties executionProperties;
    private final TaskBoardTimelineSupport timelineSupport;
    private final ReactTaskBoardAuditService auditService;

    public Optional<ReactTaskBoardState> load(String assistantMsgId) {
        return store.load(assistantMsgId);
    }

    public ReactTaskBoardApplyResult apply(
            String assistantMsgId,
            boolean merge,
            List<TaskBoardItemInput> inputs,
            List<String> forbiddenKeys) {
        if (assistantMsgId == null || assistantMsgId.isBlank()) {
            return ReactTaskBoardApplyResult.failure("缺少 assistantMsgId，无法维护任务清单");
        }
        if (forbiddenKeys != null && !forbiddenKeys.isEmpty()) {
            return ReactTaskBoardApplyResult.failure(
                    "任务清单不支持 DAG 字段（" + String.join(", ", forbiddenKeys) + "），请改用 plan-workflow");
        }
        if (inputs == null || inputs.isEmpty()) {
            return ReactTaskBoardApplyResult.failure("items 不能为空");
        }
        int maxItems = maxItems();
        if (!merge && inputs.size() > maxItems) {
            return ReactTaskBoardApplyResult.failure("任务数量超过上限 " + maxItems);
        }
        List<TaskBoardItemView> normalized = new ArrayList<>();
        for (TaskBoardItemInput input : inputs) {
            String error = validateItem(input);
            if (error != null) {
                return ReactTaskBoardApplyResult.failure(error);
            }
            normalized.add(new TaskBoardItemView(
                    StringUtils.hasText(input.id()) ? input.id().strip() : null,
                    input.content().strip(),
                    input.status().strip().toLowerCase(Locale.ROOT)));
        }
        enforceSingleInProgress(normalized);
        ReactTaskBoardState current = store.load(assistantMsgId).orElse(null);
        List<TaskBoardItemView> merged = merge
                ? mergeItems(current != null ? current.items() : List.of(), normalized, maxItems)
                : assignIds(normalized);
        if (merged.size() > maxItems) {
            return ReactTaskBoardApplyResult.failure("任务数量超过上限 " + maxItems);
        }
        int revision = current != null ? current.revision() + 1 : 1;
        String boardId = current != null ? current.boardId() : UUID.randomUUID().toString();
        ReactTaskBoardState next = new ReactTaskBoardState(
                boardId,
                assistantMsgId.strip(),
                revision,
                System.currentTimeMillis(),
                List.copyOf(merged));
        store.save(next);
        auditService.onUpdated(next);
        String summary = progressSummary(merged);
        return ReactTaskBoardApplyResult.success(revision, summary, merged);
    }

    public void emitTimelineUpdate(ProcessingTimelineSession session, ReactTaskBoardApplyResult result) {
        if (session == null || result == null || !result.ok()) {
            return;
        }
        timelineSupport.applyUpdate(session, result.items(), result.revision(), result.summary());
    }

    public void finalizeTimeline(ProcessingTimelineSession session, String assistantMsgId) {
        if (session == null || assistantMsgId == null || assistantMsgId.isBlank()) {
            return;
        }
        if (!session.hasStep(TimelineStepId.TASKS.id())) {
            return;
        }
        store.load(assistantMsgId).ifPresent(board -> {
            auditService.persistFinal(board);
            timelineSupport.completeOnRunEnd(session, board.items(), board.revision(), progressSummary(board.items()));
        });
    }

    /** 供单测 / 无 Redis 场景直接写板 */
    void saveState(ReactTaskBoardState state) {
        store.save(state);
    }

    static String progressSummary(List<TaskBoardItemView> items) {
        if (items == null || items.isEmpty()) {
            return "0/0 已完成";
        }
        long completed = items.stream().filter(i -> "completed".equals(i.status())).count();
        return completed + "/" + items.size() + " 已完成";
    }

    static boolean allTerminal(List<TaskBoardItemView> items) {
        if (items == null || items.isEmpty()) {
            return false;
        }
        return items.stream().allMatch(i ->
                "completed".equals(i.status()) || "cancelled".equals(i.status()));
    }

    static String findActiveTask(List<TaskBoardItemView> items) {
        if (items == null) {
            return "";
        }
        return items.stream()
                .filter(i -> "in_progress".equals(i.status()))
                .map(TaskBoardItemView::content)
                .findFirst()
                .orElse("");
    }

    private int maxItems() {
        AgentExecutionProperties.React react = executionProperties.getReact();
        if (react == null || react.getTaskboard() == null) {
            return 12;
        }
        return Math.max(1, react.getTaskboard().getMaxItems());
    }

    private static String validateItem(TaskBoardItemInput input) {
        if (input == null) {
            return "items 元素不能为空";
        }
        if (!StringUtils.hasText(input.content())) {
            return "任务 content 不能为空";
        }
        String content = input.content().strip();
        if (content.length() > 200) {
            return "任务 content 不能超过 200 字";
        }
        if (!StringUtils.hasText(input.status())) {
            return "任务 status 不能为空";
        }
        String status = input.status().strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED_STATUS.contains(status)) {
            return "非法 status: " + input.status();
        }
        return null;
    }

    private static void enforceSingleInProgress(List<TaskBoardItemView> items) {
        boolean seen = false;
        for (int i = 0; i < items.size(); i++) {
            TaskBoardItemView item = items.get(i);
            if (!"in_progress".equals(item.status())) {
                continue;
            }
            if (!seen) {
                seen = true;
                continue;
            }
            items.set(i, new TaskBoardItemView(item.id(), item.content(), "pending"));
        }
    }

    private static List<TaskBoardItemView> assignIds(List<TaskBoardItemView> items) {
        List<TaskBoardItemView> result = new ArrayList<>();
        int next = 1;
        for (TaskBoardItemView item : items) {
            String id = StringUtils.hasText(item.id()) ? item.id().strip() : "t" + next++;
            result.add(new TaskBoardItemView(id, item.content(), item.status()));
        }
        return result;
    }

    private static List<TaskBoardItemView> mergeItems(
            List<TaskBoardItemView> existing,
            List<TaskBoardItemView> updates,
            int maxItems) {
        Map<String, TaskBoardItemView> map = new LinkedHashMap<>();
        for (TaskBoardItemView item : existing) {
            map.put(item.id(), item);
        }
        int nextId = nextTaskId(map);
        for (TaskBoardItemView update : updates) {
            if (StringUtils.hasText(update.id()) && map.containsKey(update.id())) {
                map.put(update.id(), update);
                continue;
            }
            String matchedId = findIdByContent(map, update.content());
            if (matchedId != null) {
                map.put(matchedId, new TaskBoardItemView(matchedId, update.content(), update.status()));
                continue;
            }
            if (map.size() >= maxItems) {
                break;
            }
            String id = StringUtils.hasText(update.id()) ? update.id().strip() : "t" + nextId++;
            map.put(id, new TaskBoardItemView(id, update.content(), update.status()));
        }
        return List.copyOf(map.values());
    }

    private static String findIdByContent(Map<String, TaskBoardItemView> map, String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        String normalized = content.strip();
        for (TaskBoardItemView item : map.values()) {
            if (normalized.equals(item.content())) {
                return item.id();
            }
        }
        return null;
    }

    private static int nextTaskId(Map<String, TaskBoardItemView> map) {
        int max = 0;
        for (String id : map.keySet()) {
            Matcher matcher = TASK_ID.matcher(id);
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max + 1;
    }

    public static Set<String> forbiddenFields(Map<String, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        return raw.keySet().stream()
                .filter(FORBIDDEN_FIELDS::contains)
                .sorted()
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }
}
