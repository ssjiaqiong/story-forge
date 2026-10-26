-- 卷（分卷 / 第一卷、第二卷……）的独立实体。
-- 卷原来是章节上的两个冗余字段（volume_no / volume_title），改卷名要逐章改、空卷无法表达。
-- 这里把卷提成正经实体，但**章节的冗余字段保留**：列表、导出、管理端、拆书预览都按 volume_no
-- 分组，冗余字段由 VolumeService 单向维护即可，不用全工程改查询。
CREATE TABLE t_volume (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    volume_no integer NOT NULL,
    title varchar(120),
    intro text,
    sort_no integer NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_volume PRIMARY KEY (id),
    CONSTRAINT ck_volume_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_volume_no CHECK ((volume_no > 0))
);

CREATE UNIQUE INDEX uk_volume_novel_no ON t_volume (user_id, novel_id, volume_no) WHERE (deleted = 0);
CREATE INDEX idx_volume_novel ON t_volume (novel_id, sort_no);
CREATE INDEX idx_volume_user ON t_volume (user_id);

COMMENT ON TABLE t_volume IS '小说的卷（分卷）实体；章节的 volume_no/volume_title 由它单向维护';
COMMENT ON COLUMN t_volume.volume_no IS '卷序号，从 1 开始；与章节 volume_no 对应，换序时两侧一起改';
COMMENT ON COLUMN t_volume.intro IS '卷简介，可选';
COMMENT ON COLUMN t_volume.title IS '卷名，可为空：为空表示作者还没起名，展示时按「第 N 卷」兜底';
COMMENT ON COLUMN t_volume.sort_no IS '排序值，与 volume_no 同值（卷数不多，直接用序号）';
