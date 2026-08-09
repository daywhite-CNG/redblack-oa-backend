ALTER TABLE office_workbench_application
    ADD COLUMN application_version INT UNSIGNED NOT NULL DEFAULT 1 AFTER submission_round;
