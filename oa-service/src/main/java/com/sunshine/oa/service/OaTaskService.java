package com.sunshine.oa.service;

import com.sunshine.oa.dto.OaTaskVO;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Service
public class OaTaskService {

    private static final List<OaTaskVO> MOCK = List.of(
            new OaTaskVO(2001, "请假审批-张三年假", "leave", "pending", "部门经理", "2026-06-20"),
            new OaTaskVO(2002, "合同会签-采购框架", "contract", "pending", "法务", "2026-06-22"),
            new OaTaskVO(2003, "会议室预定冲突处理", "admin", "done", "行政", "2026-06-15"),
            new OaTaskVO(2004, "出差申请-上海客户拜访", "travel", "pending", "直属领导", "2026-06-25"),
            new OaTaskVO(2005, "用印申请-合作协议", "seal", "done", "办公室", "2026-06-10")
    );

    public List<OaTaskVO> list(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            return MOCK;
        }
        String normalized = status.trim().toLowerCase(Locale.ROOT);
        List<OaTaskVO> out = new ArrayList<>();
        for (OaTaskVO task : MOCK) {
            if (normalized.equals(task.status())) {
                out.add(task);
            }
        }
        return out;
    }
}
