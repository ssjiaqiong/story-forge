-- ============================================================================
-- V11：长篇生成质量——事实层
--
-- 背景：正文提示词的「前情」一直用 t_chapter.summary（*这一章要写什么* 的大纲），
-- 不是「*这一章实际写了什么*」。正文一旦偏离大纲，后续章节理解全错，偏差逐章累积。
-- 本次把「事实」独立成一层：
--   1) t_chapter 加两列：fact_summary（本章实际发生了什么）、fact_source（谁写的）；
--   2) 新表 t_foreshadow：伏笔台账，埋 / 收都有据可查，超期能被体检发现；
--   3) 新表 t_character_state：角色状态快照（位置 / 身体状态 / 认知），只记变化。
-- 三张都只增不改，旧代码忽略新列新表，回退镜像不受影响。
-- ============================================================================

-- 1) 章节事实摘要 ------------------------------------------------------------
ALTER TABLE t_chapter ADD COLUMN fact_summary text;
ALTER TABLE t_chapter ADD COLUMN fact_source varchar(16);

ALTER TABLE t_chapter ADD CONSTRAINT ck_chapter_fact_source
    CHECK (fact_source IS NULL OR fact_source IN ('AI', 'MANUAL', 'BACKFILL'));

COMMENT ON COLUMN t_chapter.fact_summary IS '本章实际发生的事实摘要（150~250 字）：谁做了什么、结果如何、留下什么未解决问题；与 summary（计划）分开保存';
COMMENT ON COLUMN t_chapter.fact_source IS '事实摘要来源：AI 分析产出 / MANUAL 作者手改 / BACKFILL 回填；为空表示还没产出';

-- 2) 伏笔台账 ----------------------------------------------------------------
CREATE TABLE t_foreshadow (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    title varchar(200) NOT NULL,
    content text,
    planted_chapter_no integer,
    planted_chapter_id bigint,
    planned_payoff_chapter integer,
    status varchar(16) DEFAULT 'OPEN' NOT NULL,
    payoff_chapter_no integer,
    payoff_chapter_id bigint,
    note varchar(500),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_foreshadow PRIMARY KEY (id),
    CONSTRAINT ck_foreshadow_deleted CHECK (deleted IN (0, 1)),
    CONSTRAINT ck_foreshadow_status CHECK (status IN ('OPEN', 'PAID', 'DROPPED'))
);

CREATE INDEX idx_foreshadow_novel ON t_foreshadow (novel_id, status, planted_chapter_no);
CREATE INDEX idx_foreshadow_user ON t_foreshadow (user_id);

COMMENT ON TABLE t_foreshadow IS '伏笔台账：埋了什么、计划何时回收、是否已回收';
COMMENT ON COLUMN t_foreshadow.title IS '伏笔一句话，如「母亲留下的铜钥匙」';
COMMENT ON COLUMN t_foreshadow.content IS '埋了什么、读者应该记住什么';
COMMENT ON COLUMN t_foreshadow.planned_payoff_chapter IS '计划在第几章回收；为空时按「埋设章 + 默认周期」判超期';
COMMENT ON COLUMN t_foreshadow.status IS 'OPEN 未回收 / PAID 已回收 / DROPPED 作者放弃';
COMMENT ON COLUMN t_foreshadow.payoff_chapter_no IS '实际回收章号；PAID 时有值';

-- 3) 角色状态快照 ------------------------------------------------------------
CREATE TABLE t_character_state (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_no integer NOT NULL,
    chapter_id bigint,
    character_id bigint NOT NULL,
    location varchar(200),
    condition_text varchar(500),
    mood varchar(200),
    knows_added text,
    knows_hidden text,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT pk_character_state PRIMARY KEY (id),
    CONSTRAINT ck_character_state_deleted CHECK (deleted IN (0, 1))
);

CREATE INDEX idx_char_state_char ON t_character_state (novel_id, character_id, chapter_no);
CREATE INDEX idx_char_state_user ON t_character_state (user_id);

COMMENT ON TABLE t_character_state IS '角色状态快照：只记发生变化的章节，查询时取「章号 <= N 的最新一条」';
COMMENT ON COLUMN t_character_state.location IS '本章结束时所在位置';
COMMENT ON COLUMN t_character_state.condition_text IS '身体 / 精神状态，如「左臂骨折，失血过多」';
COMMENT ON COLUMN t_character_state.knows_added IS '本章新得知的信息，一条一行';
COMMENT ON COLUMN t_character_state.knows_hidden IS '本章被隐瞒或误信的信息，一条一行';
