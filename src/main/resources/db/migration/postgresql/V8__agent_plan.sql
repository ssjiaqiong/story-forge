-- 智能助手「任务计划」：把一条复杂指令拆成可跟踪的步骤，后台连续执行到完成。
-- 解决三个老问题：轮数硬上限导致半途而废、必须手动回「继续」、看不到真实完成度。
--   1) t_novel_agent_plan       一次指令 = 一条计划（含整体进度）
--   2) t_novel_agent_plan_step  计划下的每一步（状态、回复、执行过的操作）

CREATE TABLE t_novel_agent_plan (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    session_id bigint,
    goal text NOT NULL,
    status varchar(16) DEFAULT 'PLANNING' NOT NULL,
    step_total integer DEFAULT 0 NOT NULL,
    step_done integer DEFAULT 0 NOT NULL,
    percent integer DEFAULT 0 NOT NULL,
    model varchar(128),
    error_message varchar(500),
    paused_reason varchar(255),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_novel_agent_plan PRIMARY KEY (id),
    CONSTRAINT ck_novel_agent_plan_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_novel_agent_plan_percent CHECK ((percent >= 0 AND percent <= 100)),
    CONSTRAINT ck_novel_agent_plan_steps CHECK ((step_total >= 0 AND step_done >= 0)),
    CONSTRAINT ck_novel_agent_plan_status CHECK (((status)::text = ANY ((ARRAY['PLANNING'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'DONE'::character varying, 'FAILED'::character varying, 'CANCELLED'::character varying])::text[])))
);

COMMENT ON TABLE t_novel_agent_plan IS '智能助手任务计划：一条复杂指令对应一份步骤清单与整体进度';
COMMENT ON COLUMN t_novel_agent_plan.goal IS '用户原始指令原文，后续每步都围绕它执行';
COMMENT ON COLUMN t_novel_agent_plan.status IS 'PLANNING 规划中 / RUNNING 执行中 / PAUSED 暂停待人工 / DONE 完成 / FAILED 失败 / CANCELLED 已取消';
COMMENT ON COLUMN t_novel_agent_plan.percent IS '按已完成步数计算的百分比，仅用于展示';
COMMENT ON COLUMN t_novel_agent_plan.paused_reason IS '暂停原因，例如连续两步失败';

CREATE INDEX idx_novel_agent_plan_novel ON t_novel_agent_plan (user_id, novel_id, status);
CREATE INDEX idx_novel_agent_plan_user ON t_novel_agent_plan (user_id, deleted);

CREATE TABLE t_novel_agent_plan_step (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    plan_id bigint NOT NULL,
    step_no integer NOT NULL,
    title varchar(200) NOT NULL,
    detail text,
    status varchar(16) DEFAULT 'PENDING' NOT NULL,
    reply text,
    ops_json text,
    error_message varchar(500),
    started_at timestamp,
    finished_at timestamp,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_novel_agent_plan_step PRIMARY KEY (id),
    CONSTRAINT ck_novel_agent_plan_step_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_novel_agent_plan_step_no CHECK ((step_no > 0)),
    CONSTRAINT ck_novel_agent_plan_step_status CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'DONE'::character varying, 'FAILED'::character varying, 'SKIPPED'::character varying])::text[])))
);

COMMENT ON TABLE t_novel_agent_plan_step IS '智能助手任务的单步：标题给作者看，detail 是执行时给模型的细化说明';
COMMENT ON COLUMN t_novel_agent_plan_step.status IS 'PENDING 待执行 / RUNNING 执行中 / DONE 完成 / FAILED 失败 / SKIPPED 跳过（前序步骤已覆盖或无需操作）';
COMMENT ON COLUMN t_novel_agent_plan_step.ops_json IS '该步执行过的操作摘要（AgentOperationVO 数组 JSON），用于前端展开查看';
COMMENT ON COLUMN t_novel_agent_plan_step.reply IS '该步完成后模型给作者的一句话说明';

CREATE UNIQUE INDEX uk_novel_agent_plan_step ON t_novel_agent_plan_step (user_id, plan_id, step_no);
CREATE INDEX idx_novel_agent_plan_step_plan ON t_novel_agent_plan_step (user_id, plan_id);
