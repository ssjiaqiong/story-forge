-- ============================================================================
-- V11：长篇生成质量——事实层
--
-- 背景：正文提示词的「前情」一直用 t_chapter.summary（*这一章要写什么* 的大纲），
-- 不是「*这一章实际写了什么*」。正文一旦偏离大纲，后续章节理解全错，偏差逐章累积。
-- 本次把「事实」独立成一层：
--   1) t_chapter 加两列：fact_summary（本章实际发生了什么）、fact_source（谁写的）；
--   2) 新表 t_foreshadow：伏笔台账，埋 / 收都有据可查，超期能被体检发现；
--   3) 新表 t_character_state：角色状态快照（位置 / 身体状态 / 认知），只记变化。
-- SQLite 不支持给已有表追加 CHECK 约束，fact_source 取值由服务层保证。
-- 三张都只增不改，旧代码忽略新列新表，回退镜像不受影响。
-- ============================================================================

-- 1) 章节事实摘要 ------------------------------------------------------------
ALTER TABLE t_chapter ADD COLUMN fact_summary text;
ALTER TABLE t_chapter ADD COLUMN fact_source varchar(16);

-- 2) 伏笔台账 ----------------------------------------------------------------
CREATE TABLE t_foreshadow (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    title text NOT NULL,
    content text,
    planted_chapter_no integer,
    planted_chapter_id integer,
    planned_payoff_chapter integer,
    status text DEFAULT 'OPEN' NOT NULL,
    payoff_chapter_no integer,
    payoff_chapter_id integer,
    note text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_foreshadow_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_foreshadow_status CHECK ((status IN ('OPEN', 'PAID', 'DROPPED')))
);

CREATE INDEX idx_foreshadow_novel ON t_foreshadow (novel_id, status, planted_chapter_no);
CREATE INDEX idx_foreshadow_user ON t_foreshadow (user_id);

-- 3) 角色状态快照 ------------------------------------------------------------
CREATE TABLE t_character_state (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    chapter_no integer NOT NULL,
    chapter_id integer,
    character_id integer NOT NULL,
    location text,
    condition_text text,
    mood text,
    knows_added text,
    knows_hidden text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_character_state_deleted CHECK ((deleted IN (0, 1)))
);

CREATE INDEX idx_char_state_char ON t_character_state (novel_id, character_id, chapter_no);
CREATE INDEX idx_char_state_user ON t_character_state (user_id);
