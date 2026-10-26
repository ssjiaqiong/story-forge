-- 章节正文历史版本：保存前把库内当前内容存为一版，当前稿永远只在 t_chapter。
CREATE TABLE t_chapter_version (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    chapter_id integer NOT NULL,
    version_no integer NOT NULL,
    source text NOT NULL,
    title text,
    content text,
    word_count integer DEFAULT 0 NOT NULL,
    note text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_version_deleted CHECK ((deleted IN (0, 1)))
);

CREATE UNIQUE INDEX uk_chapter_version_no ON t_chapter_version (chapter_id, version_no);
CREATE INDEX idx_chapter_version_chapter ON t_chapter_version (chapter_id, version_no DESC);
CREATE INDEX idx_chapter_version_novel ON t_chapter_version (novel_id, created_at DESC);
CREATE INDEX idx_chapter_version_user ON t_chapter_version (user_id);
