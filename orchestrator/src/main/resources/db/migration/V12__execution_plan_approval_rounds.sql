-- 动态 Plan 用户确认轮次持久化
ALTER TABLE execution_plan
    ADD COLUMN approval_rounds MEDIUMTEXT NULL COMMENT 'Plan 用户确认轮次 JSON' AFTER planner_attempts;
