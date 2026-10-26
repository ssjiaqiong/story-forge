-- 一致性 / 矛盾检查的问题台账。
-- 一次检查（规则层 + 可选 AI 层）共用同一个 run_id，前端默认只展示最近一次 run 的结果；
-- 问题落库而不是每次现算，是为了能「忽略」与「标记已修复」，让作者沉淀「这条我看过了」。
CREATE TABLE t_consistency_issue (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    run_id varchar(40) NOT NULL COMMENT '一次检查的批次号，前端默认只展示最近一次',
    source varchar(8) NOT NULL COMMENT 'RULE 规则层 / AI 模型层',
    chapter_id bigint COMMENT '问题所在章节；全书级问题（如断号）为空',
    chapter_no int COMMENT '问题所在章号；全书级问题为空',
    chapter_title varchar(200),
    issue_type varchar(32) NOT NULL COMMENT 'CHARACTER_STATE/ITEM_STATE/TIMELINE/SETTING_CONFLICT/ADDRESS/FORESHADOW/OUTLINE_DRIFT/REPEAT/STRUCTURE/ARCHIVE',
    severity varchar(4) NOT NULL COMMENT 'P0 硬伤 / P1 需注意 / P2 提示',
    title varchar(200) NOT NULL,
    detail text COMMENT '问题说明',
    evidence varchar(500) COMMENT '正文依据或判定依据，没有证据的问题不产出',
    suggestion varchar(500) COMMENT '修改建议',
    status varchar(16) DEFAULT 'OPEN' NOT NULL COMMENT 'OPEN 待处理 / IGNORED 已忽略 / FIXED 已修复',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_consistency_issue PRIMARY KEY (id),
    CONSTRAINT ck_consistency_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_consistency_source CHECK ((source IN ('RULE', 'AI'))),
    CONSTRAINT ck_consistency_severity CHECK ((severity IN ('P0', 'P1', 'P2'))),
    CONSTRAINT ck_consistency_status CHECK ((status IN ('OPEN', 'IGNORED', 'FIXED')))
) COMMENT='一致性 / 矛盾检查的问题台账（规则层与 AI 层共用）';

CREATE INDEX idx_consistency_novel ON t_consistency_issue (novel_id, status, severity);
CREATE INDEX idx_consistency_run ON t_consistency_issue (run_id);
CREATE INDEX idx_consistency_chapter ON t_consistency_issue (chapter_id);
CREATE INDEX idx_consistency_user ON t_consistency_issue (user_id);
