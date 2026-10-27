-- 自动写书（Auto Write）：给一本书配一个定时任务，到点自动从总纲 → 大纲 → 正文推进，直到完结或人工喊停。
-- 本次迁移两件事：
--   1) t_novel 增加「预计完结字数」，供「直到完结」模式判定何时收尾（0 表示未设置）；
--   2) 新建任务配置表 t_auto_write_task（一本书一个任务）与执行流水表 t_auto_write_run。

ALTER TABLE t_novel ADD COLUMN planned_words integer DEFAULT 0 NOT NULL;

COMMENT ON COLUMN t_novel.planned_words IS '预计完结字数；0 表示未设置（此时不可选「直到完结」模式）';

CREATE TABLE t_auto_write_task (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    enabled smallint DEFAULT 1 NOT NULL,
    cron_expr varchar(64) NOT NULL,
    chapters_per_run integer DEFAULT 3 NOT NULL,
    end_mode varchar(16) DEFAULT 'CHAPTERS' NOT NULL,
    end_chapter_no integer DEFAULT 0 NOT NULL,
    hard_issue_limit integer DEFAULT 5 NOT NULL,
    consistency_ai smallint DEFAULT 1 NOT NULL,
    target_words_per_chapter integer DEFAULT 3000 NOT NULL,
    status varchar(16) DEFAULT 'IDLE' NOT NULL,
    closing_triggered smallint DEFAULT 0 NOT NULL,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    total_runs integer DEFAULT 0 NOT NULL,
    generated_chapters integer DEFAULT 0 NOT NULL,
    last_run_at timestamp,
    last_result varchar(255),
    stopped_reason varchar(255),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_auto_write_task PRIMARY KEY (id),
    CONSTRAINT ck_auto_write_task_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_auto_write_task_enabled CHECK ((enabled = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_auto_write_task_ai CHECK ((consistency_ai = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_auto_write_task_closing CHECK ((closing_triggered = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_auto_write_task_per_run CHECK ((chapters_per_run >= 1 AND chapters_per_run <= 20)),
    CONSTRAINT ck_auto_write_task_hard CHECK ((hard_issue_limit >= 0)),
    CONSTRAINT ck_auto_write_task_words CHECK ((target_words_per_chapter > 0)),
    CONSTRAINT ck_auto_write_task_end_mode CHECK (((end_mode)::text = ANY ((ARRAY['CHAPTERS'::character varying, 'UNTIL_END'::character varying])::text[]))),
    CONSTRAINT ck_auto_write_task_status CHECK (((status)::text = ANY ((ARRAY['IDLE'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'STOPPED'::character varying, 'COMPLETED'::character varying])::text[])))
);

CREATE UNIQUE INDEX uk_auto_write_novel ON t_auto_write_task (user_id, novel_id) WHERE (deleted = 0);
CREATE INDEX idx_auto_write_status ON t_auto_write_task (status);
CREATE INDEX idx_auto_write_user ON t_auto_write_task (user_id);

COMMENT ON TABLE t_auto_write_task IS '自动写书定时任务配置：一本书一个任务（uk_auto_write_novel）';
COMMENT ON COLUMN t_auto_write_task.enabled IS '是否启用定时：1 启用 / 0 暂停（区别于 status 运行态）';
COMMENT ON COLUMN t_auto_write_task.cron_expr IS 'cron 表达式（Spring 6 段，秒级）';
COMMENT ON COLUMN t_auto_write_task.chapters_per_run IS '每次触发生成的章节数（1~20）';
COMMENT ON COLUMN t_auto_write_task.end_mode IS 'CHAPTERS 写到目标章数 / UNTIL_END 直到完结（需 planned_words>0）';
COMMENT ON COLUMN t_auto_write_task.end_chapter_no IS 'CHAPTERS 模式的目标章数';
COMMENT ON COLUMN t_auto_write_task.hard_issue_limit IS '体检硬伤（P0）熔断阈值，默认 5';
COMMENT ON COLUMN t_auto_write_task.consistency_ai IS '体检是否跑 AI 深检：1 是 / 0 只跑规则层';
COMMENT ON COLUMN t_auto_write_task.target_words_per_chapter IS '每章目标字数，默认 3000';
COMMENT ON COLUMN t_auto_write_task.status IS 'IDLE 空闲 / RUNNING 执行中 / PAUSED 已暂停（含熔断）/ STOPPED 已停止 / COMPLETED 已完成';
COMMENT ON COLUMN t_auto_write_task.closing_triggered IS 'UNTIL_END 模式收尾评估是否已触发（只触发一次）';
COMMENT ON COLUMN t_auto_write_task.consecutive_failures IS '连续失败周期数，达到 3 停止任务';
COMMENT ON COLUMN t_auto_write_task.total_runs IS '累计执行轮次';
COMMENT ON COLUMN t_auto_write_task.generated_chapters IS '本任务累计生成的章节数';
COMMENT ON COLUMN t_auto_write_task.last_result IS '上次执行结果摘要';
COMMENT ON COLUMN t_auto_write_task.stopped_reason IS '停止原因（人工暂停 / 体检熔断 / 连续失败）';

CREATE TABLE t_auto_write_run (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    task_id bigint NOT NULL,
    started_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    finished_at timestamp,
    status varchar(16) NOT NULL,
    chapters_written integer DEFAULT 0 NOT NULL,
    outlines_created integer DEFAULT 0 NOT NULL,
    master_outlines_created integer DEFAULT 0 NOT NULL,
    consistency_hard integer,
    message varchar(500),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_auto_write_run PRIMARY KEY (id),
    CONSTRAINT ck_auto_write_run_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_auto_write_run_status CHECK (((status)::text = ANY ((ARRAY['SUCCEEDED'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying])::text[])))
);

CREATE INDEX idx_auto_write_run_task ON t_auto_write_run (task_id, started_at DESC);
CREATE INDEX idx_auto_write_run_novel ON t_auto_write_run (novel_id);
CREATE INDEX idx_auto_write_run_user ON t_auto_write_run (user_id);

COMMENT ON TABLE t_auto_write_run IS '自动写书执行流水（保留最近 50 轮，超出物理删除）';
COMMENT ON COLUMN t_auto_write_run.status IS 'SUCCEEDED 成功 / FAILED 失败 / SKIPPED 上轮未跑完跳过';
COMMENT ON COLUMN t_auto_write_run.chapters_written IS '本轮写成章数';
COMMENT ON COLUMN t_auto_write_run.outlines_created IS '本轮补的章节大纲数';
COMMENT ON COLUMN t_auto_write_run.master_outlines_created IS '本轮补的总纲数';
COMMENT ON COLUMN t_auto_write_run.consistency_hard IS '本轮体检的 P0（硬伤）数；未体检为 NULL';
COMMENT ON COLUMN t_auto_write_run.message IS '摘要或失败原因';
