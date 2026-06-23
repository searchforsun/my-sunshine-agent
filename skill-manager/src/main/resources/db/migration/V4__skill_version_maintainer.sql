ALTER TABLE skill_version
    ADD COLUMN maintainer VARCHAR(64) NULL AFTER status;
