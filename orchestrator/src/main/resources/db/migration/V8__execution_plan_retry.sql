-- Plan 重试审计：Planner attempt 记录
ALTER TABLE execution_plan
    ADD COLUMN planner_attempts MEDIUMTEXT NULL AFTER plan_json,
    ADD COLUMN replan_count INT NOT NULL DEFAULT 0 AFTER planner_attempts;
