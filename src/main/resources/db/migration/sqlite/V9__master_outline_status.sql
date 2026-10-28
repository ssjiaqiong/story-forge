-- 总纲从「一堆平行的段落」升级为「顺序推进的阶段」：一条总纲讲完才开下一条。
-- 原来自动写书按固定「每条总纲覆盖 10 章」硬切，总纲没讲完就被判满、后续大纲被跳过。
-- 本次加两列（SQLite 不支持给已有表追加 CHECK 约束，取值由服务层保证）：
--   1) status           未开始 / 进行中 / 已完成，自动写书按它串行推进；
--   2) planned_outlines 预计大纲数量，只作参考（不是硬上限），由模型规划时给出。

ALTER TABLE t_master_outline ADD COLUMN status varchar(16) DEFAULT 'PENDING' NOT NULL;
ALTER TABLE t_master_outline ADD COLUMN planned_outlines integer DEFAULT 0 NOT NULL;

CREATE INDEX idx_master_outline_status ON t_master_outline (user_id, novel_id, status, sort_no);
