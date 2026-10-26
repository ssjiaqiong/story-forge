-- 章节正文历史版本：保存前把库内当前内容存为一版，当前稿永远只在 t_chapter。
-- 这样「回滚」= 把某一版写回 t_chapter，AI 覆盖前的稿子也能被救回来。
CREATE TABLE t_chapter_version (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    version_no integer NOT NULL,
    source varchar(16) NOT NULL,
    title varchar(200),
    content text,
    word_count integer DEFAULT 0 NOT NULL,
    note varchar(200),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_chapter_version PRIMARY KEY (id)
);

CREATE UNIQUE INDEX uk_chapter_version_no ON t_chapter_version (chapter_id, version_no);
CREATE INDEX idx_chapter_version_chapter ON t_chapter_version (chapter_id, version_no DESC);
CREATE INDEX idx_chapter_version_novel ON t_chapter_version (novel_id, created_at DESC);
CREATE INDEX idx_chapter_version_user ON t_chapter_version (user_id);

COMMENT ON TABLE t_chapter_version IS '章节正文历史版本（保存前的旧内容快照）';
COMMENT ON COLUMN t_chapter_version.version_no IS '章内递增序号，从 1 开始';
COMMENT ON COLUMN t_chapter_version.source IS 'MANUAL/AI_BODY/AI_REVISE/BATCH/RESTORE/IMPORT/AUTO';
COMMENT ON COLUMN t_chapter_version.title IS '当时的章节标题，回滚标题时用得上';
COMMENT ON COLUMN t_chapter_version.content IS '快照正文（保存前的内容）';
COMMENT ON COLUMN t_chapter_version.note IS '备注，如「AI 写正文 · 覆盖」「回滚自第 3 版」';
