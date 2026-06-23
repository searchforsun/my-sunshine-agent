ALTER TABLE skill_version
    ADD COLUMN side_effect VARCHAR(32) NOT NULL DEFAULT 'read' AFTER max_iters,
    ADD COLUMN sandbox VARCHAR(32) NOT NULL DEFAULT 'none' AFTER side_effect,
    ADD COLUMN references_json VARCHAR(1024) NOT NULL DEFAULT '[]' AFTER sandbox,
    ADD COLUMN scripts_json VARCHAR(1024) NOT NULL DEFAULT '[]' AFTER references_json;
