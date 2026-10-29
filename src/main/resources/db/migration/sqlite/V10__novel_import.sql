-- 整本 TXT 导入：把「只剩正文」的成稿导入成一本可续写的书。
-- 与拆书（t_book_digest）的区别：拆书只读不落原文、产出改编大纲；导入要
--   1) 原文按章完整落库（t_chapter.content），一个字不丢；
--   2) AI 逆推出设定、人物、势力、物品、总纲与逐章大纲，回填成可继续写作的书。
-- 两者任务模型相同（异步 + 百分比 + 分步标签），故单独建表，互不干扰。

CREATE TABLE t_novel_import (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    source_name text,
    title text,
    pen_name text,
    genre text,
    source_chars integer DEFAULT 0 NOT NULL,
    chapter_count integer DEFAULT 0 NOT NULL,
    volume_count integer DEFAULT 0 NOT NULL,
    imported_chapters integer DEFAULT 0 NOT NULL,
    status text DEFAULT 'RUNNING' NOT NULL,
    percent integer DEFAULT 0 NOT NULL,
    step_no integer DEFAULT 0 NOT NULL,
    step_total integer DEFAULT 8 NOT NULL,
    step_label text,
    result_json text,
    error_message text,
    novel_id integer,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_import_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_import_status CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED')))
);

CREATE INDEX idx_novel_import_user ON t_novel_import (user_id, status, created_at);
CREATE INDEX idx_novel_import_novel ON t_novel_import (novel_id);
