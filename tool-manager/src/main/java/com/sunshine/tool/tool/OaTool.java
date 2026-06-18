package com.sunshine.tool.tool;

import com.sunshine.tool.client.OaServiceClient;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OaTool {

    private final OaServiceClient oaClient;

    @Tool(name = "list_oa_tasks",
            description = "查询 OA 待办任务。用户问请假审批、合同会签、出差/用印等待办时使用。"
                    + "status 可选 pending / done / all。")
    public String listOaTasks(
            @ToolParam(name = "status", description = "pending | done | all，默认 pending")
            String status) {
        log.info("[OaTool] list_oa_tasks status={}", status);
        return oaClient.listTasksText(status);
    }
}
