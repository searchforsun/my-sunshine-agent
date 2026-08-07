package com.sunshine.oa.tools;

import com.sunshine.oa.model.OaTask;
import com.sunshine.oa.service.OaBizService;
import com.sunshine.tools.sdk.annotation.SunshineTool;
import com.sunshine.tools.sdk.annotation.ToolParam;
import com.sunshine.tools.sdk.context.ToolInvocationContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Component
@RequiredArgsConstructor
public class OaSunshineTools {

    private final OaBizService oaBizService;

    @SunshineTool(
            id = "list_oa_tasks",
            displayName = "查询 OA 待办",
            description = "查询当前用户负责的 OA 待办任务。用户问请假审批、合同会签、出差/用印等待办时使用。",
            timelineSummaryTemplate = "{count} 条 OA 待办",
            timelineSummaryExtract = "{\"count\":\"regex:共\\\\s*(\\\\d+)\\\\s*条\"}")
    public String listOaTasks(
            @ToolParam(value = "status", description = "pending | done | all", required = false) String status) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        List<OaTask> tasks = oaBizService.listTasks(tenantId, userId, status != null ? status : "pending");
        if (tasks.isEmpty()) {
            return "未查询到符合条件的 OA 待办。";
        }
        StringBuilder sb = new StringBuilder("共 ").append(tasks.size()).append(" 条 OA 待办：\n");
        for (OaTask task : tasks) {
            sb.append("- [").append(task.id()).append("] ")
                    .append(task.title())
                    .append(" | 分类=").append(task.category())
                    .append(" | 状态=").append(task.status())
                    .append(" | 负责人=").append(task.assigneeUserId())
                    .append('\n');
        }
        return sb.toString().trim();
    }

    @SunshineTool(
            id = "approve_oa_task",
            displayName = "审批 OA 待办",
            description = "审批指定 OA 待办任务（写操作）。仅负责人可批；参数 taskId 为待办编号；执行确认由平台时间线处理。",
            sideEffect = "write",
            timelineSummaryTemplate = "{output}")
    public String approveOaTask(
            @ToolParam(value = "taskId", description = "待办任务 ID，如 task-b1", required = false) String taskId) {
        String userId = ToolInvocationContext.requireUserId();
        String tenantId = ToolInvocationContext.tenantIdOrDefault();
        if (!StringUtils.hasText(taskId)) {
            return "请提供待办 taskId。";
        }
        return oaBizService.approveTask(tenantId, userId, taskId.trim())
                .map(t -> "已审批待办 " + t.id()
                        + " | 标题=" + t.title()
                        + " | 状态=" + t.status())
                .orElse("无权审批或不存在 taskId=" + taskId.trim());
    }
}
