package com.sunshine.workflow.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Plan 校验结果 — 聚合结构 / 数据流问题 */
public final class WorkflowPlanValidationResult {

    private final List<String> issues = new ArrayList<>();

    public void add(String issue) {
        if (issue != null && !issue.isBlank()) {
            issues.add(issue.strip());
        }
    }

    public boolean isValid() {
        return issues.isEmpty();
    }

    public List<String> issues() {
        return Collections.unmodifiableList(issues);
    }

    public String firstIssue() {
        return issues.isEmpty() ? null : issues.get(0);
    }

    public String joinedIssues() {
        return String.join("\n", issues);
    }
}
