-- 一致性 / 矛盾检查的问题台账。
-- 一次检查（规则层 + 可选 AI 层）共用同一个 run_id，前端默认只展示最近一次 run 的结果；
-- 问题落库而不是每次现算，是为了能「忽略」与「标记已修复」，让作者沉淀「这条我看过了」。
CREATE TABLE t_consistency_issue (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    run_id text NOT NULL,
    source text NOT NULL,
    chapter_id integer,
    chapter_no integer,
    chapter_title text,
    issue_type text NOT NULL,
    severity text NOT NULL,
    title text NOT NULL,
    detail text,
    evidence text,
    suggestion text,
    status text DEFAULT 'OPEN' NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_consistency_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_consistency_source CHECK ((source IN ('RULE', 'AI'))),
    CONSTRAINT ck_consistency_severity CHECK ((severity IN ('P0', 'P1', 'P2'))),
    CONSTRAINT ck_consistency_status CHECK ((status IN ('OPEN', 'IGNORED', 'FIXED')))
);

CREATE INDEX idx_consistency_novel ON t_consistency_issue (novel_id, status, severity);
CREATE INDEX idx_consistency_run ON t_consistency_issue (run_id);
CREATE INDEX idx_consistency_chapter ON t_consistency_issue (chapter_id);
CREATE INDEX idx_consistency_user ON t_consistency_issue (user_id);
