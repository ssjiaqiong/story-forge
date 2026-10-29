-- 整本 TXT 导入：把「只剩正文」的成稿导入成一本可续写的书。
-- 与拆书（t_book_digest）的区别：拆书只读不落原文、产出改编大纲；导入要
--   1) 原文按章完整落库（t_chapter.content），一个字不丢；
--   2) AI 逆推出设定、人物、势力、物品、总纲与逐章大纲，回填成可继续写作的书。
-- 两者任务模型相同（异步 + 百分比 + 分步标签），故单独建表，互不干扰。

CREATE TABLE t_novel_import (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    source_name varchar(200) COMMENT '上传文件名',
    title varchar(200) COMMENT '导入后生成的书名',
    pen_name varchar(64) COMMENT '笔名（可空）',
    genre varchar(32) COMMENT '题材，由 AI 判定',
    source_chars int DEFAULT 0 NOT NULL COMMENT '原文汉字数',
    chapter_count int DEFAULT 0 NOT NULL COMMENT '识别出的章节总数',
    volume_count int DEFAULT 0 NOT NULL COMMENT '卷数',
    imported_chapters int DEFAULT 0 NOT NULL COMMENT '已落库章节数',
    status varchar(16) DEFAULT 'RUNNING' NOT NULL COMMENT 'RUNNING / SUCCEEDED / FAILED',
    percent int DEFAULT 0 NOT NULL,
    step_no int DEFAULT 0 NOT NULL,
    step_total int DEFAULT 8 NOT NULL,
    step_label varchar(64),
    result_json text COMMENT '逆推结果摘要：世界观/人物/物品/势力/总纲条数与模型信息',
    error_message varchar(500),
    novel_id bigint COMMENT '生成的小说 ID',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_novel_import PRIMARY KEY (id),
    CONSTRAINT ck_novel_import_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_import_status CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED')))
) COMMENT='整本 TXT 导入任务：原文切章落库 + AI 逆推设定/人物/物品/总纲/逐章大纲';

CREATE INDEX idx_novel_import_user ON t_novel_import (user_id, status, created_at);
CREATE INDEX idx_novel_import_novel ON t_novel_import (novel_id);
