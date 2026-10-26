-- 卷（分卷 / 第一卷、第二卷……）的独立实体。
-- 卷原来是章节上的两个冗余字段（volume_no / volume_title），改卷名要逐章改、空卷无法表达。
-- 这里把卷提成正经实体，但**章节的冗余字段保留**：列表、导出、管理端、拆书预览都按 volume_no
-- 分组，冗余字段由 VolumeService 单向维护即可，不用全工程改查询。
CREATE TABLE t_volume (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    volume_no int NOT NULL,
    title varchar(120),
    intro longtext,
    sort_no int NOT NULL,
    deleted tinyint DEFAULT 0 NOT NULL,
    created_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at datetime DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT pk_volume PRIMARY KEY (id),
    CONSTRAINT ck_volume_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_volume_no CHECK ((volume_no > 0))
) COMMENT = '小说的卷（分卷）实体；章节的 volume_no/volume_title 由它单向维护';

-- MySQL 不支持部分索引：唯一性用条件函数列等价实现（删除的行该列为 NULL，不参与唯一）
CREATE UNIQUE INDEX uk_volume_novel_no ON t_volume (user_id, novel_id, volume_no, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE INDEX idx_volume_novel ON t_volume (novel_id, sort_no);
CREATE INDEX idx_volume_user ON t_volume (user_id);
