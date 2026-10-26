-- 章节正文历史版本：保存前把库内当前内容存为一版，当前稿永远只在 t_chapter。
CREATE TABLE t_chapter_version (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    version_no int NOT NULL COMMENT '章内递增序号，从 1 开始',
    source varchar(16) NOT NULL COMMENT 'MANUAL/AI_BODY/AI_REVISE/BATCH/RESTORE/IMPORT/AUTO',
    title varchar(200) COMMENT '当时的章节标题，回滚标题时用得上',
    content text COMMENT '快照正文（保存前的内容）',
    word_count int DEFAULT 0 NOT NULL,
    note varchar(200) COMMENT '备注，如「AI 写正文 · 覆盖」「回滚自第 3 版」',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_chapter_version PRIMARY KEY (id),
    CONSTRAINT ck_chapter_version_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='章节正文历史版本（保存前的旧内容快照）';

CREATE UNIQUE INDEX uk_chapter_version_no ON t_chapter_version (chapter_id, version_no);
CREATE INDEX idx_chapter_version_chapter ON t_chapter_version (chapter_id, version_no DESC);
CREATE INDEX idx_chapter_version_novel ON t_chapter_version (novel_id, created_at DESC);
CREATE INDEX idx_chapter_version_user ON t_chapter_version (user_id);
