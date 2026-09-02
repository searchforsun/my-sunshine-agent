package com.sunshine.orchestrator.taskboard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * fast 跨轮任务板恢复（M0）：新消息装配时读同 conversation 最近一条 task_board 终态快照，
 * 存在未完成任务 → 渲染【任务清单】块；全完成/无快照/解析失败 → 不注入。
 * 只读，不写执行态（task-list-memory §5.1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskBoardRestoreService {

    private final TaskBoardRepository repository;
    private final ObjectMapper objectMapper;

    private static final TypeReference<List<TaskBoardItemView>> ITEMS_TYPE = new TypeReference<>() {
    };

    public Optional<String> renderRestoreBlock(String conversationId) {
        if (!StringUtils.hasText(conversationId)) {
            return Optional.empty();
        }
        return repository.findFirstByConversationIdOrderByUpdatedAtDesc(conversationId)
                .flatMap(entity -> toItems(conversationId, entity))
                .filter(items -> !items.isEmpty() && !TaskBoardService.allTerminal(items))
                .map(TaskBoardService::renderTaskListBlock);
    }

    private Optional<List<TaskBoardItemView>> toItems(String conversationId, TaskBoardEntity entity) {
        try {
            List<TaskBoardItemView> items = objectMapper.readValue(entity.getItemsJson(), ITEMS_TYPE);
            return items == null ? Optional.of(List.of()) : Optional.of(items);
        } catch (Exception e) {
            log.warn("[TaskBoardRestore] items 解析失败 conv={}: {}", conversationId, e.getMessage());
            return Optional.empty();
        }
    }
}
