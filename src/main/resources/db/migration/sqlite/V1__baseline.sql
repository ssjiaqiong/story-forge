-- =============================================================================
-- StoryForge 数据库基线（V1 合并版 · SQLite）
--
-- 本文件由 script/db/gen-baseline.mjs 从 postgresql/V1__baseline.sql 派生，
-- 禁止手工修改：结构变更请改 PostgreSQL 版并重跑脚本，三份脚本始终同源。
--
-- 目标版本：SQLite 3.35+
--
-- 与 PostgreSQL 版有意保留的差异：
--   1. 部分索引（WHERE 子句）SQLite 原生支持，与 PostgreSQL 写法一致，无需改写。
--   2. 时间列一律为 TEXT，格式 yyyy-MM-dd HH:mm:ss.SSS，由 SqliteLocalDateTimeTypeHandler
--      负责读写转换；DEFAULT 用 datetime('now','localtime')，与 PostgreSQL 一样存本地时间。
-- =============================================================================



-- ---------------------------- t_ai_config ----------------------------
CREATE TABLE t_ai_config (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    name text NOT NULL,
    base_url text NOT NULL,
    api_key_cipher text,
    api_key_mask text,
    default_model text,
    model_list text,
    timeout_ms integer DEFAULT 60000 NOT NULL,
    temperature numeric DEFAULT 0.80 NOT NULL,
    max_tokens integer DEFAULT 4096 NOT NULL,
    top_p numeric DEFAULT 1.00 NOT NULL,
    extra_headers text,
    enabled integer DEFAULT 1 NOT NULL,
    is_default integer DEFAULT 0 NOT NULL,
    remark text,
    last_test_at text,
    last_test_ok integer,
    last_test_latency_ms integer,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    kind text DEFAULT 'TEXT' NOT NULL,
    provider text DEFAULT 'OPENAI' NOT NULL,
    image_size text,
    CONSTRAINT ck_ai_config_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_ai_config_kind CHECK ((kind IN ('TEXT', 'IMAGE'))),
    CONSTRAINT ck_ai_config_max_tokens CHECK (((max_tokens >= 1) AND (max_tokens <= 200000))),
    CONSTRAINT ck_ai_config_temperature CHECK (((temperature >= (0)) AND (temperature <= (2)))),
    CONSTRAINT ck_ai_config_timeout CHECK (((timeout_ms >= 1000) AND (timeout_ms <= 600000))),
    CONSTRAINT ck_ai_config_top_p CHECK (((top_p >= (0)) AND (top_p <= (1))))
);

-- ---------------------------- t_ai_usage ----------------------------
CREATE TABLE t_ai_usage (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    scene text NOT NULL,
    model text,
    prompt_tokens integer DEFAULT 0 NOT NULL,
    completion_tokens integer DEFAULT 0 NOT NULL,
    total_tokens integer DEFAULT 0 NOT NULL,
    latency_ms integer,
    success integer DEFAULT 1 NOT NULL,
    error_message text,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    CONSTRAINT ck_ai_usage_success CHECK ((success IN (0, 1))),
    CONSTRAINT ck_ai_usage_tokens CHECK (((prompt_tokens >= 0) AND (completion_tokens >= 0) AND (total_tokens >= 0)))
);

-- ---------------------------- t_audit_log ----------------------------
CREATE TABLE t_audit_log (
    id integer NOT NULL PRIMARY KEY,
    user_id integer,
    username text,
    action text NOT NULL,
    target_type text,
    target_id text,
    detail text,
    ip text,
    result text NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    CONSTRAINT ck_audit_log_result CHECK ((result IN ('SUCCESS', 'FAIL')))
);

-- ---------------------------- t_book_digest ----------------------------
CREATE TABLE t_book_digest (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    source_name text,
    source_chars integer DEFAULT 0 NOT NULL,
    source_chapters integer DEFAULT 0 NOT NULL,
    adaptation_level text DEFAULT 'MEDIUM' NOT NULL,
    background_direction text,
    chapter_target integer DEFAULT 20 NOT NULL,
    status text DEFAULT 'RUNNING' NOT NULL,
    percent integer DEFAULT 0 NOT NULL,
    step_no integer DEFAULT 0 NOT NULL,
    step_total integer DEFAULT 6 NOT NULL,
    step_label text,
    result_json text,
    result_title text,
    error_message text,
    generated_novel_id integer,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_book_digest_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_book_digest_level CHECK ((adaptation_level IN ('LIGHT', 'MEDIUM', 'HEAVY', 'REBUILD'))),
    CONSTRAINT ck_book_digest_status CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED')))
);

-- ---------------------------- t_change_history ----------------------------
CREATE TABLE t_change_history (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    chapter_id integer NOT NULL,
    chapter_no integer,
    chapter_title text,
    analysis_id integer,
    entity_type text NOT NULL,
    entity_id integer NOT NULL,
    entity_name text NOT NULL,
    field_key text NOT NULL,
    field_label text,
    before_state text,
    after_state text,
    reason text,
    evidence text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_change_history_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_change_history_entity_type CHECK ((entity_type IN ('CHARACTER', 'ITEM', 'FACTION', 'CHARACTER_ATTRIBUTE', 'INVENTORY')))
);

-- ---------------------------- t_chapter ----------------------------
CREATE TABLE t_chapter (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    volume_no integer DEFAULT 1 NOT NULL,
    volume_title text,
    chapter_no integer DEFAULT 1 NOT NULL,
    title text NOT NULL,
    summary text,
    content text,
    word_count integer DEFAULT 0 NOT NULL,
    status text DEFAULT 'DRAFT' NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    master_outline_id integer,
    CONSTRAINT ck_chapter_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_chapter_status CHECK ((status IN ('DRAFT', 'WRITING', 'DONE')))
);

-- ---------------------------- t_chapter_analysis ----------------------------
CREATE TABLE t_chapter_analysis (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    chapter_id integer NOT NULL,
    chapter_no integer,
    chapter_title text,
    word_count integer DEFAULT 0 NOT NULL,
    target_words integer DEFAULT 0 NOT NULL,
    word_pass integer DEFAULT 0 NOT NULL,
    outline_score integer DEFAULT 0 NOT NULL,
    ai_score integer DEFAULT 0 NOT NULL,
    model text,
    latency_ms integer DEFAULT 0 NOT NULL,
    result_json text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_analysis_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_chapter_analysis_word_pass CHECK ((word_pass IN (0, 1)))
);

-- ---------------------------- t_chapter_character ----------------------------
CREATE TABLE t_chapter_character (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    chapter_id integer NOT NULL,
    character_id integer NOT NULL,
    role_desc text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_character_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_chapter_event ----------------------------
CREATE TABLE t_chapter_event (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    chapter_id integer NOT NULL,
    event_id integer NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_event_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_character ----------------------------
CREATE TABLE t_character (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    name text NOT NULL,
    alias text,
    gender text,
    age_desc text,
    identity text,
    role_pos text DEFAULT 'SUPPORTING' NOT NULL,
    life_status text DEFAULT 'ALIVE' NOT NULL,
    first_chapter integer,
    faction_id integer,
    profile_json text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_character_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_character_life CHECK ((life_status IN ('ALIVE', 'DEAD', 'MISSING', 'UNKNOWN'))),
    CONSTRAINT ck_character_role CHECK ((role_pos IN ('PROTAGONIST', 'SUPPORTING', 'ANTAGONIST', 'MINOR')))
);

-- ---------------------------- t_character_attribute ----------------------------
CREATE TABLE t_character_attribute (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    character_id integer NOT NULL,
    name text NOT NULL,
    value text,
    unit text,
    attr_type text DEFAULT 'NUMBER' NOT NULL,
    note text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_attribute_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_attribute_type CHECK ((attr_type IN ('NUMBER', 'TEXT')))
);

-- ---------------------------- t_character_inventory ----------------------------
CREATE TABLE t_character_inventory (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    character_id integer NOT NULL,
    entry_type text DEFAULT 'MISC' NOT NULL,
    item_id integer,
    name text NOT NULL,
    quantity numeric,
    unit text,
    status text DEFAULT 'HELD' NOT NULL,
    note text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inventory_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_inventory_entry CHECK ((entry_type IN ('ITEM', 'CURRENCY', 'MISC'))),
    CONSTRAINT ck_inventory_status CHECK ((status IN ('HELD', 'LOST', 'CONSUMED')))
);

-- ---------------------------- t_character_relation ----------------------------
CREATE TABLE t_character_relation (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    from_character_id integer NOT NULL,
    to_character_id integer NOT NULL,
    relation_type text NOT NULL,
    description text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_character_relation_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_character_relation_self CHECK ((from_character_id <> to_character_id)),
    CONSTRAINT ck_character_relation_type CHECK ((relation_type IN ('MASTER_APPRENTICE', 'FAMILY', 'SPOUSE', 'LOVER', 'FRIEND', 'ALLY', 'ENEMY', 'RIVAL', 'COLLEAGUE', 'OTHER')))
);

-- ---------------------------- t_event_participant ----------------------------
CREATE TABLE t_event_participant (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    event_id integer NOT NULL,
    character_id integer NOT NULL,
    role_desc text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_event_participant_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_faction ----------------------------
CREATE TABLE t_faction (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    name text NOT NULL,
    category text,
    remark text,
    scope text,
    status text DEFAULT 'ACTIVE' NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_faction_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_faction_status CHECK ((status IN ('ACTIVE', 'DORMANT', 'DESTROYED', 'DISBANDED')))
);

-- ---------------------------- t_faction_member ----------------------------
CREATE TABLE t_faction_member (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    faction_id integer NOT NULL,
    character_id integer NOT NULL,
    "position" text NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_faction_member_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_inspiration_message ----------------------------
CREATE TABLE t_inspiration_message (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    session_id integer NOT NULL,
    role text NOT NULL,
    content text,
    model text,
    prompt_tokens integer,
    completion_tokens integer,
    status text DEFAULT 'OK' NOT NULL,
    error_message text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_message_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_inspiration_message_role CHECK ((role IN ('USER', 'ASSISTANT', 'SYSTEM'))),
    CONSTRAINT ck_inspiration_message_status CHECK ((status IN ('OK', 'FAILED')))
);

-- ---------------------------- t_inspiration_session ----------------------------
CREATE TABLE t_inspiration_session (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    title text,
    skill_code text DEFAULT 'NOVEL_CREATE' NOT NULL,
    message_count integer DEFAULT 0 NOT NULL,
    last_message_at text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_session_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_inspiration_skill ----------------------------
CREATE TABLE t_inspiration_skill (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    code text NOT NULL,
    name text NOT NULL,
    description text,
    system_prompt text NOT NULL,
    builtin integer DEFAULT 0 NOT NULL,
    enabled integer DEFAULT 1 NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_skill_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_item ----------------------------
CREATE TABLE t_item (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    name text NOT NULL,
    category text,
    owner text,
    origin text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    status text DEFAULT 'ACTIVE' NOT NULL,
    quantity integer,
    description text,
    CONSTRAINT ck_item_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_item_quantity CHECK (((quantity IS NULL) OR (quantity >= 0))),
    CONSTRAINT ck_item_status CHECK ((status IN ('ACTIVE', 'DESTROYED', 'ABANDONED', 'LOST', 'CONSUMED')))
);

-- ---------------------------- t_master_outline ----------------------------
CREATE TABLE t_master_outline (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    title text NOT NULL,
    content text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_master_outline_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_novel ----------------------------
CREATE TABLE t_novel (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    title text NOT NULL,
    pen_name text,
    genre text,
    tags text,
    intro text,
    cover_path text,
    status text DEFAULT 'DRAFT' NOT NULL,
    source text DEFAULT 'MANUAL' NOT NULL,
    inspiration_session_id integer,
    chapter_count integer DEFAULT 0 NOT NULL,
    word_count integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_source CHECK ((source IN ('MANUAL', 'INSPIRATION', 'DECONSTRUCT'))),
    CONSTRAINT ck_novel_status CHECK ((status IN ('DRAFT', 'WRITING', 'FINISHED', 'ARCHIVED')))
);

-- ---------------------------- t_novel_agent_message ----------------------------
CREATE TABLE t_novel_agent_message (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    session_id integer NOT NULL,
    role text NOT NULL,
    content text,
    operations text,
    model text,
    prompt_tokens integer,
    completion_tokens integer,
    status text DEFAULT 'OK' NOT NULL,
    error_message text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_message_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_agent_message_role CHECK ((role IN ('USER', 'ASSISTANT', 'SYSTEM'))),
    CONSTRAINT ck_novel_agent_message_status CHECK ((status IN ('OK', 'FAILED')))
);

-- ---------------------------- t_novel_agent_session ----------------------------
CREATE TABLE t_novel_agent_session (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    title text,
    message_count integer DEFAULT 0 NOT NULL,
    last_message_at text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_session_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_novel_cover ----------------------------
CREATE TABLE t_novel_cover (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    file_path text NOT NULL,
    mime_type text DEFAULT 'image/jpeg' NOT NULL,
    file_size integer,
    width integer,
    height integer,
    prompt text,
    negative_prompt text,
    provider text,
    model text,
    is_primary integer DEFAULT 0 NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    style text,
    has_text integer DEFAULT 0 NOT NULL,
    title_text text,
    author_text text,
    text_mode text DEFAULT 'OVERLAY' NOT NULL,
    CONSTRAINT ck_novel_cover_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_cover_primary CHECK ((is_primary IN (0, 1))),
    CONSTRAINT ck_novel_cover_sort CHECK (((sort_no >= 0) AND (sort_no <= 4)))
);

-- ---------------------------- t_prompt_template ----------------------------
CREATE TABLE t_prompt_template (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    scene text NOT NULL,
    content text NOT NULL,
    revision integer DEFAULT 1 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_prompt_template_deleted CHECK ((deleted IN (0, 1)))
);

-- ---------------------------- t_scene_binding ----------------------------
CREATE TABLE t_scene_binding (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    scene_code text NOT NULL,
    ai_config_id integer NOT NULL,
    model text,
    temperature numeric,
    max_tokens integer,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_scene_binding_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_scene_binding_max_tokens CHECK (((max_tokens IS NULL) OR ((max_tokens >= 1) AND (max_tokens <= 200000)))),
    CONSTRAINT ck_scene_binding_temperature CHECK (((temperature IS NULL) OR ((temperature >= (0)) AND (temperature <= (2)))))
);

-- ---------------------------- t_search_config ----------------------------
CREATE TABLE t_search_config (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    name text NOT NULL,
    provider text NOT NULL,
    base_url text,
    api_key_cipher text,
    api_key_mask text,
    default_top_k integer DEFAULT 6 NOT NULL,
    language text DEFAULT 'zh-CN' NOT NULL,
    region text,
    timeout_ms integer DEFAULT 15000 NOT NULL,
    enabled integer DEFAULT 1 NOT NULL,
    is_default integer DEFAULT 0 NOT NULL,
    remark text,
    last_test_at text,
    last_test_ok integer,
    last_test_latency_ms integer,
    last_test_message text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL
);

-- ---------------------------- t_search_scene ----------------------------
CREATE TABLE t_search_scene (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    scene_code text NOT NULL,
    config_id integer,
    enabled integer DEFAULT 1 NOT NULL,
    top_k integer,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL
);

-- ---------------------------- t_setting_doc ----------------------------
CREATE TABLE t_setting_doc (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    category text NOT NULL,
    title text NOT NULL,
    content_md text,
    inject_scope text DEFAULT 'GLOBAL' NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_setting_doc_category CHECK ((category IN ('WORLDVIEW', 'BACKGROUND', 'CORE_CONFLICT', 'HIGHLIGHT', 'STYLE'))),
    CONSTRAINT ck_setting_doc_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_setting_doc_scope CHECK ((inject_scope IN ('GLOBAL', 'CHAPTER', 'MANUAL')))
);

-- ---------------------------- t_timeline_event ----------------------------
CREATE TABLE t_timeline_event (
    id integer NOT NULL PRIMARY KEY,
    user_id integer NOT NULL,
    novel_id integer NOT NULL,
    event_time text,
    title text NOT NULL,
    description text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    status text DEFAULT 'UNUSED' NOT NULL,
    CONSTRAINT ck_timeline_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_timeline_status CHECK ((status IN ('UNUSED', 'ONGOING', 'DONE', 'ABANDONED')))
);

-- ---------------------------- t_token_blacklist ----------------------------
CREATE TABLE t_token_blacklist (
    id integer NOT NULL PRIMARY KEY,
    jti text NOT NULL,
    user_id integer,
    token_type text NOT NULL,
    expires_at text NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    CONSTRAINT ck_token_blacklist_type CHECK ((token_type IN ('access', 'refresh')))
);

-- ---------------------------- t_user ----------------------------
CREATE TABLE t_user (
    id integer NOT NULL PRIMARY KEY,
    username text NOT NULL,
    password_hash text NOT NULL,
    nickname text,
    role text DEFAULT 'USER' NOT NULL,
    status text DEFAULT 'ACTIVE' NOT NULL,
    must_change_password integer DEFAULT 0 NOT NULL,
    remark text,
    last_login_at text,
    last_login_ip text,
    deleted integer DEFAULT 0 NOT NULL,
    created_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    created_by integer,
    updated_at text DEFAULT (datetime('now','localtime')) NOT NULL,
    updated_by integer,
    version integer DEFAULT 0 NOT NULL,
    prev_login_at text,
    prev_login_ip text,
    CONSTRAINT ck_user_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_user_role CHECK ((role IN ('SUPER_ADMIN', 'USER'))),
    CONSTRAINT ck_user_status CHECK ((status IN ('ACTIVE', 'DISABLED')))
);

CREATE INDEX idx_ai_config_user ON t_ai_config (user_id);
CREATE INDEX idx_ai_usage_user_created ON t_ai_usage (user_id, created_at DESC);
CREATE INDEX idx_ai_usage_user_scene_created ON t_ai_usage (user_id, scene, created_at DESC);
CREATE INDEX idx_attribute_character ON t_character_attribute (user_id, character_id, sort_no);
CREATE INDEX idx_audit_log_action ON t_audit_log (action);
CREATE INDEX idx_audit_log_created_at ON t_audit_log (created_at);
CREATE INDEX idx_audit_log_user_id ON t_audit_log (user_id);
CREATE INDEX idx_book_digest_user_created ON t_book_digest (user_id, created_at DESC);
CREATE INDEX idx_book_digest_user_status ON t_book_digest (user_id, status);
CREATE INDEX idx_change_history_entity ON t_change_history (entity_type, entity_id);
CREATE INDEX idx_change_history_novel ON t_change_history (novel_id, chapter_no);
CREATE INDEX idx_chapter_analysis_chapter ON t_chapter_analysis (chapter_id);
CREATE INDEX idx_chapter_analysis_novel ON t_chapter_analysis (novel_id, created_at DESC);
CREATE INDEX idx_chapter_character_chapter ON t_chapter_character (chapter_id);
CREATE INDEX idx_chapter_character_character ON t_chapter_character (character_id);
CREATE INDEX idx_chapter_event_chapter ON t_chapter_event (chapter_id);
CREATE INDEX idx_chapter_event_event ON t_chapter_event (event_id);
CREATE INDEX idx_chapter_master_outline ON t_chapter (master_outline_id);
CREATE INDEX idx_chapter_novel ON t_chapter (novel_id, sort_no);
CREATE INDEX idx_chapter_user ON t_chapter (user_id);
CREATE INDEX idx_character_novel ON t_character (novel_id, sort_no);
CREATE INDEX idx_character_relation_from ON t_character_relation (from_character_id);
CREATE INDEX idx_character_relation_novel ON t_character_relation (novel_id, sort_no);
CREATE INDEX idx_character_relation_to ON t_character_relation (to_character_id);
CREATE INDEX idx_character_user ON t_character (user_id);
CREATE INDEX idx_event_participant_character ON t_event_participant (character_id);
CREATE INDEX idx_event_participant_event ON t_event_participant (event_id);
CREATE INDEX idx_faction_member_character ON t_faction_member (character_id);
CREATE INDEX idx_faction_member_faction ON t_faction_member (faction_id);
CREATE INDEX idx_faction_novel ON t_faction (novel_id, sort_no);
CREATE INDEX idx_inspiration_message_session ON t_inspiration_message (session_id, id);
CREATE INDEX idx_inspiration_message_user ON t_inspiration_message (user_id);
CREATE INDEX idx_inspiration_skill_user ON t_inspiration_skill (user_id);
CREATE INDEX idx_inventory_character ON t_character_inventory (user_id, character_id, sort_no);
CREATE INDEX idx_item_novel ON t_item (novel_id, sort_no);
CREATE INDEX idx_item_novel_status ON t_item (novel_id, status) WHERE (deleted = 0);
CREATE INDEX idx_master_outline_novel ON t_master_outline (novel_id, sort_no);
CREATE INDEX idx_master_outline_user ON t_master_outline (user_id);
CREATE INDEX idx_novel_agent_message_session ON t_novel_agent_message (session_id, id);
CREATE INDEX idx_novel_agent_message_user ON t_novel_agent_message (user_id);
CREATE INDEX idx_novel_agent_session_user ON t_novel_agent_session (user_id);
CREATE INDEX idx_novel_cover_novel ON t_novel_cover (novel_id, sort_no);
CREATE INDEX idx_novel_user_created ON t_novel (user_id, created_at DESC);
CREATE INDEX idx_scene_binding_user ON t_scene_binding (user_id);
CREATE INDEX idx_search_config_user ON t_search_config (user_id, created_at) WHERE (deleted = 0);
CREATE INDEX idx_setting_doc_novel ON t_setting_doc (novel_id, sort);
CREATE INDEX idx_timeline_novel ON t_timeline_event (novel_id, sort_no);
CREATE INDEX idx_timeline_novel_status ON t_timeline_event (novel_id, status, sort_no);
CREATE INDEX idx_token_blacklist_expires_at ON t_token_blacklist (expires_at);
CREATE INDEX idx_user_created_at ON t_user (created_at);
CREATE INDEX idx_user_role_status ON t_user (role, status);
CREATE UNIQUE INDEX uk_ai_config_default_kind ON t_ai_config (user_id, kind) WHERE ((is_default = 1) AND (deleted = 0));
CREATE UNIQUE INDEX uk_ai_config_name ON t_ai_config (user_id, name) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_attribute_name ON t_character_attribute (user_id, character_id, name) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_change_history_entry ON t_change_history (user_id, chapter_id, entity_type, entity_id, field_key) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_chapter_character ON t_chapter_character (user_id, chapter_id, character_id) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_chapter_event ON t_chapter_event (user_id, chapter_id, event_id) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_character_relation_pair ON t_character_relation (user_id, novel_id, from_character_id, to_character_id, relation_type) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_event_participant ON t_event_participant (user_id, event_id, character_id) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_faction_member ON t_faction_member (user_id, faction_id, character_id) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_inspiration_session_user ON t_inspiration_session (user_id) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_inspiration_skill_code ON t_inspiration_skill (user_id, code) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_inventory_item ON t_character_inventory (user_id, character_id, item_id) WHERE ((deleted = 0) AND (item_id IS NOT NULL));
CREATE UNIQUE INDEX uk_inventory_name ON t_character_inventory (user_id, character_id, name) WHERE ((deleted = 0) AND (item_id IS NULL));
CREATE UNIQUE INDEX uk_novel_agent_session_novel ON t_novel_agent_session (novel_id) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_novel_cover_primary ON t_novel_cover (user_id, novel_id) WHERE ((is_primary = 1) AND (deleted = 0));
CREATE UNIQUE INDEX uk_novel_cover_sort ON t_novel_cover (user_id, novel_id, sort_no) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_novel_title ON t_novel (user_id, title) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_prompt_template_scene ON t_prompt_template (user_id, scene) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_scene_binding ON t_scene_binding (user_id, scene_code) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_search_config_default ON t_search_config (user_id) WHERE ((deleted = 0) AND (is_default = 1) AND (enabled = 1));
CREATE UNIQUE INDEX uk_search_config_name ON t_search_config (user_id, name) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_search_scene ON t_search_scene (user_id, scene_code) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_setting_doc_novel_category ON t_setting_doc (user_id, novel_id, category) WHERE (deleted = 0);
CREATE UNIQUE INDEX uk_token_blacklist_jti ON t_token_blacklist (jti);
CREATE UNIQUE INDEX uk_user_username ON t_user (username) WHERE (deleted = 0);
