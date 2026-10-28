-- 智能助手「任务计划」：把一条复杂指令拆成可跟踪的步骤，后台连续执行到完成。
--   1) t_novel_agent_plan       一次指令 = 一条计划（含整体进度）
--   2) t_novel_agent_plan_step  计划下的每一步（状态、回复、执行过的操作）

CREATE TABLE t_novel_agent_plan (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    session_id integer,
    goal text NOT NULL,
    status text DEFAULT 'PLANNING' NOT NULL,
    step_total integer DEFAULT 0 NOT NULL,
    step_done integer DEFAULT 0 NOT NULL,
    percent integer DEFAULT 0 NOT NULL,
    model text,
    error_message text,
    paused_reason text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by integer,
    updated_at text DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_plan_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_novel_agent_plan_percent CHECK (percent BETWEEN 0 AND 100),
    CONSTRAINT ck_novel_agent_plan_steps CHECK (step_total >= 0 AND step_done >= 0),
    CONSTRAINT ck_novel_agent_plan_status CHECK (status IN ('PLANNING', 'RUNNING', 'PAUSED', 'DONE', 'FAILED', 'CANCELLED'))
);

CREATE INDEX idx_novel_agent_plan_novel ON t_novel_agent_plan (user_id, novel_id, status);
CREATE INDEX idx_novel_agent_plan_user ON t_novel_agent_plan (user_id, deleted);

CREATE TABLE t_novel_agent_plan_step (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    plan_id integer NOT NULL,
    step_no integer NOT NULL,
    title text NOT NULL,
    detail text,
    status text DEFAULT 'PENDING' NOT NULL,
    reply text,
    ops_json text,
    error_message text,
    started_at text,
    finished_at text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by integer,
    updated_at text DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_plan_step_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_novel_agent_plan_step_no CHECK (step_no > 0),
    CONSTRAINT ck_novel_agent_plan_step_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
);

CREATE UNIQUE INDEX uk_novel_agent_plan_step ON t_novel_agent_plan_step (user_id, plan_id, step_no);
CREATE INDEX idx_novel_agent_plan_step_plan ON t_novel_agent_plan_step (user_id, plan_id);
