-- 自动写书（Auto Write）：给一本书配一个定时任务，到点自动从总纲 → 大纲 → 正文推进，直到完结或人工喊停。
-- 本次迁移两件事：
--   1) t_novel 增加「预计完结字数」，供「直到完结」模式判定何时收尾（0 表示未设置）；
--   2) 新建任务配置表 t_auto_write_task（一本书一个任务）与执行流水表 t_auto_write_run。

ALTER TABLE t_novel ADD COLUMN planned_words integer DEFAULT 0 NOT NULL;

CREATE TABLE t_auto_write_task (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    enabled integer DEFAULT 1 NOT NULL,
    cron_expr text NOT NULL,
    chapters_per_run integer DEFAULT 3 NOT NULL,
    end_mode text DEFAULT 'CHAPTERS' NOT NULL,
    end_chapter_no integer DEFAULT 0 NOT NULL,
    hard_issue_limit integer DEFAULT 5 NOT NULL,
    consistency_ai integer DEFAULT 1 NOT NULL,
    target_words_per_chapter integer DEFAULT 3000 NOT NULL,
    status text DEFAULT 'IDLE' NOT NULL,
    closing_triggered integer DEFAULT 0 NOT NULL,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    total_runs integer DEFAULT 0 NOT NULL,
    generated_chapters integer DEFAULT 0 NOT NULL,
    last_run_at text,
    last_result text,
    stopped_reason text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_auto_write_task_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_auto_write_task_enabled CHECK ((enabled IN (0, 1))),
    CONSTRAINT ck_auto_write_task_ai CHECK ((consistency_ai IN (0, 1))),
    CONSTRAINT ck_auto_write_task_closing CHECK ((closing_triggered IN (0, 1))),
    CONSTRAINT ck_auto_write_task_per_run CHECK ((chapters_per_run >= 1 AND chapters_per_run <= 20)),
    CONSTRAINT ck_auto_write_task_hard CHECK ((hard_issue_limit >= 0)),
    CONSTRAINT ck_auto_write_task_words CHECK ((target_words_per_chapter > 0)),
    CONSTRAINT ck_auto_write_task_end_mode CHECK (end_mode IN ('CHAPTERS', 'UNTIL_END')),
    CONSTRAINT ck_auto_write_task_status CHECK (status IN ('IDLE', 'RUNNING', 'PAUSED', 'STOPPED', 'COMPLETED'))
);

CREATE UNIQUE INDEX uk_auto_write_novel ON t_auto_write_task (user_id, novel_id) WHERE (deleted = 0);
CREATE INDEX idx_auto_write_status ON t_auto_write_task (status);
CREATE INDEX idx_auto_write_user ON t_auto_write_task (user_id);

CREATE TABLE t_auto_write_run (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    task_id integer NOT NULL,
    started_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    finished_at text,
    status text NOT NULL,
    chapters_written integer DEFAULT 0 NOT NULL,
    outlines_created integer DEFAULT 0 NOT NULL,
    master_outlines_created integer DEFAULT 0 NOT NULL,
    consistency_hard integer,
    message text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_auto_write_run_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_auto_write_run_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX idx_auto_write_run_task ON t_auto_write_run (task_id, started_at);
CREATE INDEX idx_auto_write_run_novel ON t_auto_write_run (novel_id);
CREATE INDEX idx_auto_write_run_user ON t_auto_write_run (user_id);
