-- 卷（分卷 / 第一卷、第二卷……）的独立实体。
-- 卷原来是章节上的两个冗余字段（volume_no / volume_title），改卷名要逐章改、空卷无法表达。
-- 这里把卷提成正经实体，但**章节的冗余字段保留**：列表、导出、管理端、拆书预览都按 volume_no
-- 分组，冗余字段由 VolumeService 单向维护即可，不用全工程改查询。
CREATE TABLE t_volume (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    volume_no integer NOT NULL,
    title text,
    intro text,
    sort_no integer NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_volume_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_volume_no CHECK ((volume_no > 0))
);

CREATE UNIQUE INDEX uk_volume_novel_no ON t_volume (user_id, novel_id, volume_no) WHERE (deleted = 0);
CREATE INDEX idx_volume_novel ON t_volume (novel_id, sort_no);
CREATE INDEX idx_volume_user ON t_volume (user_id);
