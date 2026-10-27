-- 自动写书（Auto Write）：给一本书配一个定时任务，到点自动从总纲 → 大纲 → 正文推进，直到完结或人工喊停。
-- 本次迁移两件事：
--   1) t_novel 增加「预计完结字数」，供「直到完结」模式判定何时收尾（0 表示未设置）；
--   2) 新建任务配置表 t_auto_write_task（一本书一个任务）与执行流水表 t_auto_write_run。

ALTER TABLE t_novel ADD COLUMN planned_words int DEFAULT 0 NOT NULL COMMENT '预计完结字数；0 表示未设置（此时不可选「直到完结」模式）';

CREATE TABLE t_auto_write_task (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    enabled tinyint DEFAULT 1 NOT NULL,
    cron_expr varchar(64) NOT NULL,
    chapters_per_run int DEFAULT 3 NOT NULL,
    end_mode varchar(16) DEFAULT 'CHAPTERS' NOT NULL,
    end_chapter_no int DEFAULT 0 NOT NULL,
    hard_issue_limit int DEFAULT 5 NOT NULL,
    consistency_ai tinyint DEFAULT 1 NOT NULL,
    target_words_per_chapter int DEFAULT 3000 NOT NULL,
    status varchar(16) DEFAULT 'IDLE' NOT NULL,
    closing_triggered tinyint DEFAULT 0 NOT NULL,
    consecutive_failures int DEFAULT 0 NOT NULL,
    total_runs int DEFAULT 0 NOT NULL,
    generated_chapters int DEFAULT 0 NOT NULL,
    last_run_at datetime,
    last_result varchar(255),
    stopped_reason varchar(255),
    deleted tinyint DEFAULT 0 NOT NULL,
    created_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_auto_write_task PRIMARY KEY (id),
    CONSTRAINT ck_auto_write_task_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_auto_write_task_enabled CHECK ((enabled IN (0, 1))),
    CONSTRAINT ck_auto_write_task_ai CHECK ((consistency_ai IN (0, 1))),
    CONSTRAINT ck_auto_write_task_closing CHECK ((closing_triggered IN (0, 1))),
    CONSTRAINT ck_auto_write_task_per_run CHECK ((chapters_per_run >= 1 AND chapters_per_run <= 20)),
    CONSTRAINT ck_auto_write_task_hard CHECK ((hard_issue_limit >= 0)),
    CONSTRAINT ck_auto_write_task_words CHECK ((target_words_per_chapter > 0)),
    CONSTRAINT ck_auto_write_task_end_mode CHECK (end_mode IN ('CHAPTERS', 'UNTIL_END')),
    CONSTRAINT ck_auto_write_task_status CHECK (status IN ('IDLE', 'RUNNING', 'PAUSED', 'STOPPED', 'COMPLETED'))
) COMMENT = '自动写书定时任务配置：一本书一个任务（uk_auto_write_novel）';

-- MySQL 不支持部分索引：唯一性用条件函数列等价实现（删除的行该列为 NULL，不参与唯一）
CREATE UNIQUE INDEX uk_auto_write_novel ON t_auto_write_task (user_id, novel_id, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE INDEX idx_auto_write_status ON t_auto_write_task (status);
CREATE INDEX idx_auto_write_user ON t_auto_write_task (user_id);

CREATE TABLE t_auto_write_run (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    task_id bigint NOT NULL,
    started_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at datetime,
    status varchar(16) NOT NULL,
    chapters_written int DEFAULT 0 NOT NULL,
    outlines_created int DEFAULT 0 NOT NULL,
    master_outlines_created int DEFAULT 0 NOT NULL,
    consistency_hard int,
    message varchar(500),
    deleted tinyint DEFAULT 0 NOT NULL,
    created_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_auto_write_run PRIMARY KEY (id),
    CONSTRAINT ck_auto_write_run_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_auto_write_run_status CHECK (status IN ('SUCCEEDED', 'FAILED', 'SKIPPED'))
) COMMENT = '自动写书执行流水（保留最近 50 轮，超出物理删除）';

CREATE INDEX idx_auto_write_run_task ON t_auto_write_run (task_id, started_at);
CREATE INDEX idx_auto_write_run_novel ON t_auto_write_run (novel_id);
CREATE INDEX idx_auto_write_run_user ON t_auto_write_run (user_id);
