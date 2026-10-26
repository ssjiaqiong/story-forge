-- 一致性 / 矛盾检查的问题台账。
-- 一次检查（规则层 + 可选 AI 层）共用同一个 run_id，前端默认只展示最近一次 run 的结果；
-- 问题落库而不是每次现算，是为了能「忽略」与「标记已修复」，让作者沉淀「这条我看过了」。
CREATE TABLE t_consistency_issue (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    run_id varchar(40) NOT NULL,
    source varchar(8) NOT NULL,
    chapter_id bigint,
    chapter_no integer,
    chapter_title varchar(200),
    issue_type varchar(32) NOT NULL,
    severity varchar(4) NOT NULL,
    title varchar(200) NOT NULL,
    detail text,
    evidence varchar(500),
    suggestion varchar(500),
    status varchar(16) DEFAULT 'OPEN' NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_consistency_issue PRIMARY KEY (id),
    CONSTRAINT ck_consistency_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_consistency_source CHECK (((source)::text = ANY ((ARRAY['RULE'::character varying, 'AI'::character varying])::text[]))),
    CONSTRAINT ck_consistency_severity CHECK (((severity)::text = ANY ((ARRAY['P0'::character varying, 'P1'::character varying, 'P2'::character varying])::text[]))),
    CONSTRAINT ck_consistency_status CHECK (((status)::text = ANY ((ARRAY['OPEN'::character varying, 'IGNORED'::character varying, 'FIXED'::character varying])::text[])))
);

CREATE INDEX idx_consistency_novel ON t_consistency_issue (novel_id, status, severity);
CREATE INDEX idx_consistency_run ON t_consistency_issue (run_id);
CREATE INDEX idx_consistency_chapter ON t_consistency_issue (chapter_id);
CREATE INDEX idx_consistency_user ON t_consistency_issue (user_id);

COMMENT ON TABLE t_consistency_issue IS '一致性 / 矛盾检查的问题台账（规则层与 AI 层共用）';
COMMENT ON COLUMN t_consistency_issue.run_id IS '一次检查的批次号，前端默认只展示最近一次';
COMMENT ON COLUMN t_consistency_issue.source IS 'RULE 规则层 / AI 模型层';
COMMENT ON COLUMN t_consistency_issue.chapter_no IS '问题所在章号；全书级问题（如断号）为空';
COMMENT ON COLUMN t_consistency_issue.issue_type IS 'CHARACTER_STATE/ITEM_STATE/TIMELINE/SETTING_CONFLICT/ADDRESS/FORESHADOW/OUTLINE_DRIFT/REPEAT/STRUCTURE/ARCHIVE';
COMMENT ON COLUMN t_consistency_issue.severity IS 'P0 硬伤 / P1 需注意 / P2 提示';
COMMENT ON COLUMN t_consistency_issue.evidence IS '正文依据或判定依据，没有证据的问题不产出';
COMMENT ON COLUMN t_consistency_issue.status IS 'OPEN 待处理 / IGNORED 已忽略 / FIXED 已修复';
