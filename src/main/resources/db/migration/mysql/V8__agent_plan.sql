-- 智能助手「任务计划」：把一条复杂指令拆成可跟踪的步骤，后台连续执行到完成。
--   1) t_novel_agent_plan       一次指令 = 一条计划（含整体进度）
--   2) t_novel_agent_plan_step  计划下的每一步（状态、回复、执行过的操作）

CREATE TABLE t_novel_agent_plan (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    session_id bigint,
    goal text NOT NULL COMMENT '用户原始指令原文，后续每步都围绕它执行',
    status varchar(16) DEFAULT 'PLANNING' NOT NULL COMMENT 'PLANNING/RUNNING/PAUSED/DONE/FAILED/CANCELLED',
    step_total int DEFAULT 0 NOT NULL,
    step_done int DEFAULT 0 NOT NULL,
    percent int DEFAULT 0 NOT NULL COMMENT '按已完成步数计算的百分比',
    model varchar(128),
    error_message varchar(500),
    paused_reason varchar(255) COMMENT '暂停原因，例如连续两步失败',
    deleted tinyint DEFAULT 0 NOT NULL,
    created_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_novel_agent_plan PRIMARY KEY (id),
    CONSTRAINT ck_novel_agent_plan_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_novel_agent_plan_percent CHECK (percent BETWEEN 0 AND 100),
    CONSTRAINT ck_novel_agent_plan_steps CHECK (step_total >= 0 AND step_done >= 0),
    CONSTRAINT ck_novel_agent_plan_status CHECK (status IN ('PLANNING', 'RUNNING', 'PAUSED', 'DONE', 'FAILED', 'CANCELLED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '智能助手任务计划：一条复杂指令对应一份步骤清单与整体进度';

CREATE INDEX idx_novel_agent_plan_novel ON t_novel_agent_plan (user_id, novel_id, status);
CREATE INDEX idx_novel_agent_plan_user ON t_novel_agent_plan (user_id, deleted);

CREATE TABLE t_novel_agent_plan_step (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    plan_id bigint NOT NULL,
    step_no int NOT NULL,
    title varchar(200) NOT NULL,
    detail text COMMENT '执行该步时给模型的细化说明',
    status varchar(16) DEFAULT 'PENDING' NOT NULL COMMENT 'PENDING/RUNNING/DONE/FAILED/SKIPPED',
    reply text COMMENT '该步完成后模型给作者的一句话说明',
    ops_json text COMMENT '该步执行过的操作摘要 JSON',
    error_message varchar(500),
    started_at datetime,
    finished_at datetime,
    deleted tinyint DEFAULT 0 NOT NULL,
    created_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_novel_agent_plan_step PRIMARY KEY (id),
    CONSTRAINT ck_novel_agent_plan_step_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_novel_agent_plan_step_no CHECK (step_no > 0),
    CONSTRAINT ck_novel_agent_plan_step_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '智能助手任务的单步：标题给作者看，detail 是执行时给模型的细化说明';

CREATE UNIQUE INDEX uk_novel_agent_plan_step ON t_novel_agent_plan_step (user_id, plan_id, step_no);
CREATE INDEX idx_novel_agent_plan_step_plan ON t_novel_agent_plan_step (user_id, plan_id);
