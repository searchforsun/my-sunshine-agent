package com.sunshine.orchestrator.taskboard;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskBoardRestoreServiceTest {

    @Mock
    private TaskBoardRepository repository;

    private TaskBoardRestoreService service;

    @BeforeEach
    void setUp() {
        service = new TaskBoardRestoreService(repository, new ObjectMapper());
    }

    private TaskBoardEntity entity(String conv, String json) {
        TaskBoardEntity e = new TaskBoardEntity();
        e.setId("board-1");
        e.setMessageId("msg-1");
        e.setConversationId(conv);
        e.setItemsJson(json);
        e.setUpdatedAt(Instant.now());
        return e;
    }

    @Test
    void renderTaskListBlock_containsProgressPendingAndCompleted() {
        String block = TaskBoardService.renderTaskListBlock(List.of(
                new TaskBoardItemView("t1", "步骤一", "completed"),
                new TaskBoardItemView("t2", "步骤二", "in_progress")));
        assertThat(block).contains("【任务板】")
                .contains("进度：1/2 已完成")
                .contains("- [completed] 步骤一")
                .contains("- [in_progress] 步骤二")
                .contains("接着未完成项继续");
    }

    @Test
    void renderRestoreBlock_returnsBlock_whenSnapshotHasUnfinished() {
        when(repository.findFirstByConversationIdOrderByUpdatedAtDesc("conv-1"))
                .thenReturn(Optional.of(entity("conv-1", """
                        [{"id":"t1","content":"步骤一","status":"completed"},
                         {"id":"t2","content":"步骤二","status":"pending"}]
                        """)));
        Optional<String> block = service.renderRestoreBlock("conv-1");
        assertThat(block).isPresent();
        assertThat(block.get()).contains("进度：1/2 已完成").contains("- [pending] 步骤二");
    }

    @Test
    void renderRestoreBlock_empty_whenAllTerminal() {
        when(repository.findFirstByConversationIdOrderByUpdatedAtDesc("conv-1"))
                .thenReturn(Optional.of(entity("conv-1",
                        "[{\"id\":\"t1\",\"content\":\"完成\",\"status\":\"completed\"}]")));
        assertThat(service.renderRestoreBlock("conv-1")).isEmpty();
    }

    @Test
    void renderRestoreBlock_empty_whenNoSnapshot() {
        when(repository.findFirstByConversationIdOrderByUpdatedAtDesc("conv-1"))
                .thenReturn(Optional.empty());
        assertThat(service.renderRestoreBlock("conv-1")).isEmpty();
    }

    @Test
    void renderRestoreBlock_empty_whenItemsJsonEmptyArray() {
        when(repository.findFirstByConversationIdOrderByUpdatedAtDesc("conv-1"))
                .thenReturn(Optional.of(entity("conv-1", "[]")));
        assertThat(service.renderRestoreBlock("conv-1")).isEmpty();
    }

    @Test
    void renderRestoreBlock_empty_whenItemsJsonBroken() {
        when(repository.findFirstByConversationIdOrderByUpdatedAtDesc("conv-1"))
                .thenReturn(Optional.of(entity("conv-1", "{not-json")));
        assertThat(service.renderRestoreBlock("conv-1")).isEmpty();
    }

    @Test
    void renderRestoreBlock_empty_whenAllItemsCancelled() {
        when(repository.findFirstByConversationIdOrderByUpdatedAtDesc("conv-1"))
                .thenReturn(Optional.of(entity("conv-1",
                        "[{\"id\":\"t1\",\"content\":\"任务一\",\"status\":\"cancelled\"},"
                                + "{\"id\":\"t2\",\"content\":\"任务二\",\"status\":\"cancelled\"}]")));
        assertThat(service.renderRestoreBlock("conv-1")).isEmpty();
    }

    @Test
    void renderRestoreBlock_emptyAndSkipsRepository_whenBlankConversationId() {
        assertThat(service.renderRestoreBlock("")).isEmpty();
        assertThat(service.renderRestoreBlock(null)).isEmpty();
        verifyNoInteractions(repository);
    }
}
