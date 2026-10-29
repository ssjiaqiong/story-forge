-- 整本 TXT 导入：把「只剩正文」的成稿导入成一本可续写的书。
-- 与拆书（t_book_digest）的区别：拆书只读不落原文、产出改编大纲；导入要
--   1) 原文按章完整落库（t_chapter.content），一个字不丢；
--   2) AI 逆推出设定、人物、势力、物品、总纲与逐章大纲，回填成可继续写作的书。
-- 两者任务模型相同（异步 + 百分比 + 分步标签），故单独建表，互不干扰。

CREATE TABLE t_novel_import (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    source_name character varying(200),
    title character varying(200),
    pen_name character varying(64),
    genre character varying(32),
    source_chars integer DEFAULT 0 NOT NULL,
    chapter_count integer DEFAULT 0 NOT NULL,
    volume_count integer DEFAULT 0 NOT NULL,
    imported_chapters integer DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    percent integer DEFAULT 0 NOT NULL,
    step_no integer DEFAULT 0 NOT NULL,
    step_total integer DEFAULT 8 NOT NULL,
    step_label character varying(64),
    result_json text,
    error_message character varying(500),
    novel_id bigint,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_novel_import PRIMARY KEY (id),
    CONSTRAINT ck_novel_import_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_novel_import_status CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);

COMMENT ON TABLE t_novel_import IS '整本 TXT 导入任务：原文切章落库 + AI 逆推设定/人物/物品/总纲/逐章大纲';
COMMENT ON COLUMN t_novel_import.source_name IS '上传文件名';
COMMENT ON COLUMN t_novel_import.title IS '导入后生成的书名';
COMMENT ON COLUMN t_novel_import.source_chars IS '原文汉字数';
COMMENT ON COLUMN t_novel_import.chapter_count IS '识别出的章节总数';
COMMENT ON COLUMN t_novel_import.imported_chapters IS '已落库的章节数';
COMMENT ON COLUMN t_novel_import.result_json IS '逆推结果摘要：世界观/人物/物品/势力/总纲条数与模型信息';
COMMENT ON COLUMN t_novel_import.novel_id IS '生成的小说 ID';

CREATE INDEX idx_novel_import_user ON t_novel_import (user_id, status, created_at);
CREATE INDEX idx_novel_import_novel ON t_novel_import (novel_id);
