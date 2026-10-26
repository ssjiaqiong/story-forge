-- =============================================================================
-- StoryForge 数据库基线（V1 合并版）
--
-- 由来：由原 V1 ~ V26 共 26 个增量脚本合并而成，等价于 2026-10-23 的库结构终态。
-- 为什么合并：本项目只有一个数据库实例，从未对外发布过任何中间版本，
--             保留 26 步增量既拖慢新环境初始化，也让 SQLite 方言适配（另一套脚本）
--             必须逐一翻译每一步的 ALTER，成本高且无收益。
--
-- 生成方式：pg_dump --schema-only（PostgreSQL 18.3，--no-owner --no-privileges，
--           排除 flyway_schema_history），并剔除 psql 元命令 \restrict / \unrestrict
--           （PG 18 新增，Flyway 无法解析）。
--
-- 覆盖范围：32 张业务表、全部索引（含 deleted=0 的部分唯一索引）、CHECK 约束、
--           字段注释。
--
-- 使用约定：
--   1. 这是唯一基线，禁止再修改本文件的结构内容。
--   2. 后续任何结构变更请新增 V2__xxx.sql 递增编号，PostgreSQL 与 SQLite 各一份。
--   3. 已有库（历史上跑过 V1..V26）通过 Flyway baseline-version=1 接管，
--      本脚本不会在其上重复执行；新库则正常执行本脚本。
-- =============================================================================

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
-- 注意：不写 SET transaction_timeout（PostgreSQL 17+ 专有参数，16 会报 42704），
-- 保证基线在 PostgreSQL 16 / 17 / 18 上均可执行。
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: t_ai_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_ai_config (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    name character varying(64) NOT NULL,
    base_url character varying(500) NOT NULL,
    api_key_cipher text,
    api_key_mask character varying(64),
    default_model character varying(128),
    model_list text,
    timeout_ms integer DEFAULT 60000 NOT NULL,
    temperature numeric(3,2) DEFAULT 0.80 NOT NULL,
    max_tokens integer DEFAULT 4096 NOT NULL,
    top_p numeric(3,2) DEFAULT 1.00 NOT NULL,
    extra_headers text,
    enabled boolean DEFAULT true NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    remark character varying(255),
    last_test_at timestamp without time zone,
    last_test_ok boolean,
    last_test_latency_ms integer,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    kind character varying(16) DEFAULT 'TEXT'::character varying NOT NULL,
    provider character varying(32) DEFAULT 'OPENAI'::character varying NOT NULL,
    image_size character varying(32),
    CONSTRAINT ck_ai_config_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_ai_config_kind CHECK (((kind)::text = ANY ((ARRAY['TEXT'::character varying, 'IMAGE'::character varying])::text[]))),
    CONSTRAINT ck_ai_config_max_tokens CHECK (((max_tokens >= 1) AND (max_tokens <= 200000))),
    CONSTRAINT ck_ai_config_temperature CHECK (((temperature >= (0)::numeric) AND (temperature <= (2)::numeric))),
    CONSTRAINT ck_ai_config_timeout CHECK (((timeout_ms >= 1000) AND (timeout_ms <= 600000))),
    CONSTRAINT ck_ai_config_top_p CHECK (((top_p >= (0)::numeric) AND (top_p <= (1)::numeric)))
);


--
-- Name: TABLE t_ai_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_ai_config IS '用户级 AI 接口配置（OpenAI 协议）';


--
-- Name: COLUMN t_ai_config.user_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.user_id IS '归属用户，数据隔离键';


--
-- Name: COLUMN t_ai_config.base_url; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.base_url IS '接口基址，需含版本段，如 https://api.openai.com/v1';


--
-- Name: COLUMN t_ai_config.api_key_cipher; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.api_key_cipher IS 'API Key 密文（AES-256-GCM），禁止明文';


--
-- Name: COLUMN t_ai_config.api_key_mask; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.api_key_mask IS 'API Key 掩码，仅用于界面回显';


--
-- Name: COLUMN t_ai_config.model_list; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.model_list IS '最近一次从 /v1/models 拉取的模型列表缓存（JSON 数组文本）';


--
-- Name: COLUMN t_ai_config.extra_headers; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.extra_headers IS '附加请求头 JSON 对象文本，如 {"X-Org":"xxx"}';


--
-- Name: COLUMN t_ai_config.is_default; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.is_default IS '该用户该类型（kind）下的默认端点，每用户每类型至多一个';


--
-- Name: COLUMN t_ai_config.last_test_ok; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.last_test_ok IS '最近一次连通性测试结果';


--
-- Name: COLUMN t_ai_config.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.deleted IS '逻辑删除：0 未删除 / 1 已删除';


--
-- Name: COLUMN t_ai_config.kind; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.kind IS '接口类型：TEXT 文本 / IMAGE 图像（文生图），创建后不可变更';


--
-- Name: COLUMN t_ai_config.provider; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.provider IS '平台协议：OPENAI / SILICONFLOW / VOLCENGINE / ZHIPU / DASHSCOPE / MODELSCOPE';


--
-- Name: COLUMN t_ai_config.image_size; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_config.image_size IS '图像接口默认出图尺寸，形如 1024x1536；文本接口为空';


--
-- Name: t_ai_usage; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_ai_usage (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene character varying(32) NOT NULL,
    model character varying(120),
    prompt_tokens integer DEFAULT 0 NOT NULL,
    completion_tokens integer DEFAULT 0 NOT NULL,
    total_tokens integer DEFAULT 0 NOT NULL,
    latency_ms integer,
    success smallint DEFAULT 1 NOT NULL,
    error_message character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    CONSTRAINT ck_ai_usage_success CHECK ((success = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_ai_usage_tokens CHECK (((prompt_tokens >= 0) AND (completion_tokens >= 0) AND (total_tokens >= 0)))
);


--
-- Name: TABLE t_ai_usage; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_ai_usage IS 'AI 调用用量流水：每次模型调用一行，供仪表盘统计 token 消耗';


--
-- Name: COLUMN t_ai_usage.scene; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.scene IS 'AiScene 编码，如 BODY_GEN / OUTLINE_GEN';


--
-- Name: COLUMN t_ai_usage.model; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.model IS '服务端实际使用的模型名';


--
-- Name: COLUMN t_ai_usage.prompt_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.prompt_tokens IS '输入 token 数（服务端未返回时记 0）';


--
-- Name: COLUMN t_ai_usage.completion_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.completion_tokens IS '输出 token 数（服务端未返回时记 0）';


--
-- Name: COLUMN t_ai_usage.total_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.total_tokens IS '输入 + 输出 token 数';


--
-- Name: COLUMN t_ai_usage.latency_ms; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.latency_ms IS '端到端耗时（毫秒）';


--
-- Name: COLUMN t_ai_usage.success; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.success IS '1 成功 / 0 失败（失败时 token 可能为 0）';


--
-- Name: COLUMN t_ai_usage.error_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_ai_usage.error_message IS '失败原因，成功时为空';


--
-- Name: t_audit_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_audit_log (
    id bigint NOT NULL,
    user_id bigint,
    username character varying(64),
    action character varying(64) NOT NULL,
    target_type character varying(64),
    target_id character varying(64),
    detail character varying(2000),
    ip character varying(64),
    result character varying(16) NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_audit_log_result CHECK (((result)::text = ANY ((ARRAY['SUCCESS'::character varying, 'FAIL'::character varying])::text[])))
);


--
-- Name: TABLE t_audit_log; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_audit_log IS '关键写操作审计日志（谁、何时、改了什么）';


--
-- Name: COLUMN t_audit_log.action; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_audit_log.action IS '动作编码，如 USER_CREATE / USER_DISABLE';


--
-- Name: COLUMN t_audit_log.detail; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_audit_log.detail IS '脱敏摘要，禁止记录口令与令牌';


--
-- Name: t_book_digest; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_book_digest (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    source_name character varying(200),
    source_chars integer DEFAULT 0 NOT NULL,
    source_chapters integer DEFAULT 0 NOT NULL,
    adaptation_level character varying(16) DEFAULT 'MEDIUM'::character varying NOT NULL,
    background_direction character varying(500),
    chapter_target integer DEFAULT 20 NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    percent integer DEFAULT 0 NOT NULL,
    step_no integer DEFAULT 0 NOT NULL,
    step_total integer DEFAULT 6 NOT NULL,
    step_label character varying(64),
    result_json text,
    result_title character varying(200),
    error_message character varying(500),
    generated_novel_id bigint,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_book_digest_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_book_digest_level CHECK (((adaptation_level)::text = ANY ((ARRAY['LIGHT'::character varying, 'MEDIUM'::character varying, 'HEAVY'::character varying, 'REBUILD'::character varying])::text[]))),
    CONSTRAINT ck_book_digest_status CHECK (((status)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCEEDED'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: TABLE t_book_digest; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_book_digest IS '拆书记录：原文分析、改编结果与生成的成书';


--
-- Name: COLUMN t_book_digest.adaptation_level; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_book_digest.adaptation_level IS '改编程度：LIGHT 轻度 / MEDIUM 中度 / HEAVY 重度 / REBUILD 完全重构';


--
-- Name: COLUMN t_book_digest.background_direction; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_book_digest.background_direction IS '背景改编方向（用户填写）';


--
-- Name: COLUMN t_book_digest.result_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_book_digest.result_json IS '改编结果 JSON：主线脉络 + 设定 + 人物/关系/势力 + 大纲/事件';


--
-- Name: t_change_history; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_change_history (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    chapter_no integer,
    chapter_title character varying(200),
    analysis_id bigint,
    entity_type character varying(32) NOT NULL,
    entity_id bigint NOT NULL,
    entity_name character varying(128) NOT NULL,
    field_key character varying(100) NOT NULL,
    field_label character varying(128),
    before_state character varying(500),
    after_state character varying(500),
    reason character varying(500),
    evidence character varying(500),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_change_history_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_change_history_entity_type CHECK (((entity_type)::text = ANY ((ARRAY['CHARACTER'::character varying, 'ITEM'::character varying, 'FACTION'::character varying, 'CHARACTER_ATTRIBUTE'::character varying, 'INVENTORY'::character varying])::text[])))
);


--
-- Name: TABLE t_change_history; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_change_history IS '要素变更履历：人物 / 物品 / 势力 / 属性 / 背包的状态变迁';


--
-- Name: COLUMN t_change_history.chapter_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_change_history.chapter_id IS '产生这次变更的章节';


--
-- Name: COLUMN t_change_history.entity_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_change_history.entity_type IS 'CHARACTER / ITEM / FACTION / CHARACTER_ATTRIBUTE / INVENTORY';


--
-- Name: COLUMN t_change_history.field_key; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_change_history.field_key IS '变更字段：lifeStatus / identity / owner / status / quantity / scope 等';


--
-- Name: COLUMN t_change_history.before_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_change_history.before_state IS '原状态（取自分析前的库内值，取不到时为「—」）';


--
-- Name: COLUMN t_change_history.after_state; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_change_history.after_state IS '现状态（本章结束时的状态）';


--
-- Name: COLUMN t_change_history.evidence; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_change_history.evidence IS '正文依据，模型摘录的原句';


--
-- Name: t_chapter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_chapter (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    volume_no integer DEFAULT 1 NOT NULL,
    volume_title character varying(120),
    chapter_no integer DEFAULT 1 NOT NULL,
    title character varying(200) NOT NULL,
    summary text,
    content text,
    word_count integer DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    master_outline_id bigint,
    CONSTRAINT ck_chapter_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_chapter_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'WRITING'::character varying, 'DONE'::character varying])::text[])))
);


--
-- Name: TABLE t_chapter; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_chapter IS '小说章节：大纲阶段维护摘要，正文阶段维护内容';


--
-- Name: COLUMN t_chapter.volume_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.volume_no IS '所属卷序号，从 1 开始';


--
-- Name: COLUMN t_chapter.volume_title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.volume_title IS '卷名，如「第一卷 旧书」';


--
-- Name: COLUMN t_chapter.chapter_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.chapter_no IS '章节号，同一部小说内递增';


--
-- Name: COLUMN t_chapter.summary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.summary IS '大纲摘要：本章要写什么';


--
-- Name: COLUMN t_chapter.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.content IS '正文内容（纯文本）';


--
-- Name: COLUMN t_chapter.word_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.word_count IS '正文字数（去空白后的字符数）';


--
-- Name: COLUMN t_chapter.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.status IS 'DRAFT 草稿 / WRITING 写作中 / DONE 已完成';


--
-- Name: COLUMN t_chapter.master_outline_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter.master_outline_id IS '关联的总纲 id：本章大纲属于哪一段剧情走向，可空';


--
-- Name: t_chapter_analysis; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_chapter_analysis (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    chapter_no integer,
    chapter_title character varying(200),
    word_count integer DEFAULT 0 NOT NULL,
    target_words integer DEFAULT 0 NOT NULL,
    word_pass smallint DEFAULT 0 NOT NULL,
    outline_score integer DEFAULT 0 NOT NULL,
    ai_score integer DEFAULT 0 NOT NULL,
    model character varying(128),
    latency_ms bigint DEFAULT 0 NOT NULL,
    result_json text,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_analysis_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_chapter_analysis_word_pass CHECK ((word_pass = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_chapter_analysis; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_chapter_analysis IS '章节分析报告：字数、大纲贴合度、AI 味评分与抽取到的变更';


--
-- Name: COLUMN t_chapter_analysis.word_pass; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter_analysis.word_pass IS '字数是否达标：1 达标 0 不达标';


--
-- Name: COLUMN t_chapter_analysis.outline_score; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter_analysis.outline_score IS '与本章大纲的贴合度评分，0~100';


--
-- Name: COLUMN t_chapter_analysis.ai_score; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter_analysis.ai_score IS 'AI 率评分，0~100，0 为纯 AI 味';


--
-- Name: COLUMN t_chapter_analysis.result_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter_analysis.result_json IS '完整报告 JSON（含评分理由、变更明细、本地检测信号）';


--
-- Name: t_chapter_character; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_chapter_character (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    character_id bigint NOT NULL,
    role_desc character varying(128),
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_character_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_chapter_character; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_chapter_character IS '章节参与人物：章节与角色的多对多关联';


--
-- Name: COLUMN t_chapter_character.role_desc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_chapter_character.role_desc IS '在本章中的作用，如「视角人物」「只出现在回忆里」';


--
-- Name: t_chapter_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_chapter_event (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    event_id bigint NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_event_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_chapter_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_chapter_event IS '章节关联事件：章节与事件的多对多关联';


--
-- Name: t_character; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_character (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    name character varying(64) NOT NULL,
    alias character varying(128),
    gender character varying(16),
    age_desc character varying(32),
    identity character varying(255),
    role_pos character varying(16) DEFAULT 'SUPPORTING'::character varying NOT NULL,
    life_status character varying(16) DEFAULT 'ALIVE'::character varying NOT NULL,
    first_chapter integer,
    faction_id bigint,
    profile_json text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_character_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_character_life CHECK (((life_status)::text = ANY ((ARRAY['ALIVE'::character varying, 'DEAD'::character varying, 'MISSING'::character varying, 'UNKNOWN'::character varying])::text[]))),
    CONSTRAINT ck_character_role CHECK (((role_pos)::text = ANY ((ARRAY['PROTAGONIST'::character varying, 'SUPPORTING'::character varying, 'ANTAGONIST'::character varying, 'MINOR'::character varying])::text[])))
);


--
-- Name: TABLE t_character; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_character IS '小说角色：主角 / 配角 / 反派，设定细节存 profile_json';


--
-- Name: COLUMN t_character.identity; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character.identity IS '身份职位，如「青云宗外门弟子」';


--
-- Name: COLUMN t_character.role_pos; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character.role_pos IS '角色定位：PROTAGONIST 主角 / SUPPORTING 配角 / ANTAGONIST 反派 / MINOR 龙套';


--
-- Name: COLUMN t_character.profile_json; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character.profile_json IS '扩展设定 JSON：personality 性格 / motivation 动机 / goldenFinger 金手指 / arc 成长弧线 / appearance 外貌';


--
-- Name: t_character_attribute; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_character_attribute (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    character_id bigint NOT NULL,
    name character varying(64) NOT NULL,
    value character varying(64),
    unit character varying(16),
    attr_type character varying(16) DEFAULT 'NUMBER'::character varying NOT NULL,
    note character varying(255),
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_attribute_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_attribute_type CHECK (((attr_type)::text = ANY ((ARRAY['NUMBER'::character varying, 'TEXT'::character varying])::text[])))
);


--
-- Name: TABLE t_character_attribute; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_character_attribute IS '人物属性：只有当前值，历史变化走 t_change_history';


--
-- Name: COLUMN t_character_attribute.unit; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character_attribute.unit IS '单位：点 / 元 / 万，可空';


--
-- Name: COLUMN t_character_attribute.attr_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character_attribute.attr_type IS 'NUMBER 数值 / TEXT 文本（等级、境界这类）';


--
-- Name: t_character_inventory; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_character_inventory (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    character_id bigint NOT NULL,
    entry_type character varying(16) DEFAULT 'MISC'::character varying NOT NULL,
    item_id bigint,
    name character varying(100) NOT NULL,
    quantity numeric(18,2),
    unit character varying(16),
    status character varying(16) DEFAULT 'HELD'::character varying NOT NULL,
    note text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inventory_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_inventory_entry CHECK (((entry_type)::text = ANY ((ARRAY['ITEM'::character varying, 'CURRENCY'::character varying, 'MISC'::character varying])::text[]))),
    CONSTRAINT ck_inventory_status CHECK (((status)::text = ANY ((ARRAY['HELD'::character varying, 'LOST'::character varying, 'CONSUMED'::character varying])::text[])))
);


--
-- Name: TABLE t_character_inventory; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_character_inventory IS '人物背包：ITEM 关联物品档案，CURRENCY / MISC 为货币与常规物资';


--
-- Name: COLUMN t_character_inventory.item_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character_inventory.item_id IS 'entry_type=ITEM 时指向 t_item；其余为 NULL';


--
-- Name: COLUMN t_character_inventory.name; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character_inventory.name IS 'ITEM 冗余物品名便于展示；CURRENCY / MISC 由用户填写';


--
-- Name: COLUMN t_character_inventory.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character_inventory.status IS 'HELD 持有 / LOST 遗失 / CONSUMED 已消耗';


--
-- Name: t_character_relation; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_character_relation (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    from_character_id bigint NOT NULL,
    to_character_id bigint NOT NULL,
    relation_type character varying(32) NOT NULL,
    description character varying(255),
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_character_relation_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_character_relation_self CHECK ((from_character_id <> to_character_id)),
    CONSTRAINT ck_character_relation_type CHECK (((relation_type)::text = ANY ((ARRAY['MASTER_APPRENTICE'::character varying, 'FAMILY'::character varying, 'SPOUSE'::character varying, 'LOVER'::character varying, 'FRIEND'::character varying, 'ALLY'::character varying, 'ENEMY'::character varying, 'RIVAL'::character varying, 'COLLEAGUE'::character varying, 'OTHER'::character varying])::text[])))
);


--
-- Name: TABLE t_character_relation; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_character_relation IS '角色关系：两端 id 已按大小排序，关系语义为对称';


--
-- Name: COLUMN t_character_relation.relation_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character_relation.relation_type IS '关系类型：MASTER_APPRENTICE 师徒 / FAMILY 亲属 / SPOUSE 夫妻 / LOVER 恋人 / FRIEND 好友 / ALLY 盟友 / ENEMY 仇敌 / RIVAL 对手 / COLLEAGUE 同门 / OTHER 其他';


--
-- Name: COLUMN t_character_relation.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_character_relation.description IS '补充说明，如「韩戍是沈默的师父，当年亲手烧了旧街」';


--
-- Name: t_event_participant; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_event_participant (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    event_id bigint NOT NULL,
    character_id bigint NOT NULL,
    role_desc character varying(64),
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_event_participant_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_event_participant; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_event_participant IS '事件参与角色：事件与角色的多对多关联';


--
-- Name: COLUMN t_event_participant.role_desc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_event_participant.role_desc IS '在本事件中的身份或作用，如「线人」「目击者」';


--
-- Name: t_faction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_faction (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    category character varying(32),
    remark character varying(1000),
    scope character varying(500),
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_faction_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_faction_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DORMANT'::character varying, 'DESTROYED'::character varying, 'DISBANDED'::character varying])::text[])))
);


--
-- Name: TABLE t_faction; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_faction IS '小说势力：公司 / 帮派 / 国家 / 组织等';


--
-- Name: COLUMN t_faction.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_faction.category IS '势力类型，自由文本，常用值：公司 / 帮派 / 国家 / 组织 / 其他';


--
-- Name: COLUMN t_faction.scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_faction.scope IS '营业范围或管辖范围';


--
-- Name: COLUMN t_faction.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_faction.status IS '状态：ACTIVE 活跃 / DORMANT 蛰伏 / DESTROYED 覆灭 / DISBANDED 解散';


--
-- Name: t_faction_member; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_faction_member (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    faction_id bigint NOT NULL,
    character_id bigint NOT NULL,
    "position" character varying(64) NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_faction_member_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_faction_member; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_faction_member IS '势力成员：角色在某势力中的职位';


--
-- Name: COLUMN t_faction_member."position"; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_faction_member."position" IS '在该势力中的职位，自由文本，如 董事长 / 堂主 / 户部尚书';


--
-- Name: t_inspiration_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_inspiration_message (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    session_id bigint NOT NULL,
    role character varying(16) NOT NULL,
    content text,
    model character varying(128),
    prompt_tokens integer,
    completion_tokens integer,
    status character varying(16) DEFAULT 'OK'::character varying NOT NULL,
    error_message character varying(500),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_message_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_inspiration_message_role CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT ck_inspiration_message_status CHECK (((status)::text = ANY ((ARRAY['OK'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: TABLE t_inspiration_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_inspiration_message IS '灵感会话消息：USER / ASSISTANT，按 id 升序即时间序';


--
-- Name: COLUMN t_inspiration_message.role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_message.role IS '消息角色：USER 用户 / ASSISTANT AI / SYSTEM 系统';


--
-- Name: COLUMN t_inspiration_message.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_message.status IS 'OK 正常 / FAILED AI 调用失败（界面回显错误气泡，不进入上下文）';


--
-- Name: COLUMN t_inspiration_message.error_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_message.error_message IS '调用失败原因，仅 FAILED 时有值';


--
-- Name: t_inspiration_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_inspiration_session (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    title character varying(120),
    skill_code character varying(64) DEFAULT 'NOVEL_CREATE'::character varying NOT NULL,
    message_count integer DEFAULT 0 NOT NULL,
    last_message_at timestamp without time zone,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_session_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_inspiration_session; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_inspiration_session IS '灵感模式会话：每个用户至多一条，首次进入时自动创建';


--
-- Name: COLUMN t_inspiration_session.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_session.title IS '会话标题，取首条用户消息前若干字；清空上下文后重置';


--
-- Name: COLUMN t_inspiration_session.skill_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_session.skill_code IS '当前选中的技能编码';


--
-- Name: COLUMN t_inspiration_session.message_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_session.message_count IS '当前上下文消息条数（清空上下文后归零）';


--
-- Name: COLUMN t_inspiration_session.last_message_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_session.last_message_at IS '最后一条消息时间，用于列表排序与展示';


--
-- Name: t_inspiration_skill; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_inspiration_skill (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    code character varying(64) NOT NULL,
    name character varying(64) NOT NULL,
    description character varying(500),
    system_prompt text NOT NULL,
    builtin boolean DEFAULT false NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_skill_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_inspiration_skill; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_inspiration_skill IS '灵感模式技能：系统提示词 + 元信息，按用户隔离';


--
-- Name: COLUMN t_inspiration_skill.code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_skill.code IS '技能编码；内置技能固定为 NOVEL_CREATE，自定义技能由系统生成';


--
-- Name: COLUMN t_inspiration_skill.system_prompt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_skill.system_prompt IS '技能系统提示词，作为每轮对话的 system 消息';


--
-- Name: COLUMN t_inspiration_skill.builtin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_skill.builtin IS '是否内置技能：内置技能可改可恢复默认，但不可删除';


--
-- Name: COLUMN t_inspiration_skill.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_inspiration_skill.deleted IS '逻辑删除：0 未删除 / 1 已删除';


--
-- Name: t_item; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_item (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    category character varying(32),
    owner character varying(100),
    origin text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    quantity integer,
    description text,
    CONSTRAINT ck_item_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_item_quantity CHECK (((quantity IS NULL) OR (quantity >= 0))),
    CONSTRAINT ck_item_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DESTROYED'::character varying, 'ABANDONED'::character varying, 'LOST'::character varying, 'CONSUMED'::character varying])::text[])))
);


--
-- Name: TABLE t_item; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_item IS '小说物品道具：法宝 / 信物 / 关键线索等';


--
-- Name: COLUMN t_item.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_item.category IS '类别，如 法宝 / 信物 / 线索 / 消耗品';


--
-- Name: COLUMN t_item.owner; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_item.owner IS '当前持有者（角色名或势力）';


--
-- Name: COLUMN t_item.origin; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_item.origin IS '说明：物品来历、经手人、关联的关键事件或秘密';


--
-- Name: COLUMN t_item.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_item.status IS '状态：ACTIVE 活跃 / DESTROYED 摧毁 / ABANDONED 废弃 / LOST 遗失 / CONSUMED 已消耗';


--
-- Name: COLUMN t_item.quantity; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_item.quantity IS '数量，可空；唯一或不可计数的物品留空';


--
-- Name: COLUMN t_item.description; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_item.description IS '物品描述：外貌形态、材质、尺寸、磨损标记、特殊特征与象征意义';


--
-- Name: t_master_outline; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_master_outline (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    title character varying(200) NOT NULL,
    content text,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_master_outline_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_master_outline; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_master_outline IS '小说总纲：一段粗粒度剧情走向，指导章节大纲生成';


--
-- Name: COLUMN t_master_outline.sort_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_master_outline.sort_no IS '排序号，从 1 开始递增';


--
-- Name: COLUMN t_master_outline.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_master_outline.title IS '总纲标题，如「第一幕 落难与激活」';


--
-- Name: COLUMN t_master_outline.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_master_outline.content IS '总纲正文：这一段故事要发生什么，只写剧情走向，不写人物档案';


--
-- Name: t_novel; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_novel (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    title character varying(200) NOT NULL,
    pen_name character varying(64),
    genre character varying(64),
    tags text,
    intro text,
    cover_path character varying(500),
    status character varying(16) DEFAULT 'DRAFT'::character varying NOT NULL,
    source character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    inspiration_session_id bigint,
    chapter_count integer DEFAULT 0 NOT NULL,
    word_count integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_novel_source CHECK (((source)::text = ANY ((ARRAY['MANUAL'::character varying, 'INSPIRATION'::character varying, 'DECONSTRUCT'::character varying])::text[]))),
    CONSTRAINT ck_novel_status CHECK (((status)::text = ANY ((ARRAY['DRAFT'::character varying, 'WRITING'::character varying, 'FINISHED'::character varying, 'ARCHIVED'::character varying])::text[])))
);


--
-- Name: TABLE t_novel; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_novel IS '小说主表：数据隔离键为 user_id';


--
-- Name: COLUMN t_novel.tags; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel.tags IS '标签 JSON 数组文本，如 ["升级流","群像"]';


--
-- Name: COLUMN t_novel.intro; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel.intro IS '简介，来自灵感对话提炼或用户手填';


--
-- Name: COLUMN t_novel.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel.status IS 'DRAFT 草稿 / WRITING 连载中 / FINISHED 已完结 / ARCHIVED 已归档';


--
-- Name: COLUMN t_novel.source; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel.source IS '来源：MANUAL 手动创建 / INSPIRATION 灵感模式一键创建';


--
-- Name: COLUMN t_novel.inspiration_session_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel.inspiration_session_id IS '来源灵感会话 ID，便于回溯创作过程';


--
-- Name: t_novel_agent_message; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_novel_agent_message (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    session_id bigint NOT NULL,
    role character varying(16) NOT NULL,
    content text,
    operations text,
    model character varying(128),
    prompt_tokens integer,
    completion_tokens integer,
    status character varying(16) DEFAULT 'OK'::character varying NOT NULL,
    error_message character varying(500),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_message_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_novel_agent_message_role CHECK (((role)::text = ANY ((ARRAY['USER'::character varying, 'ASSISTANT'::character varying, 'SYSTEM'::character varying])::text[]))),
    CONSTRAINT ck_novel_agent_message_status CHECK (((status)::text = ANY ((ARRAY['OK'::character varying, 'FAILED'::character varying])::text[])))
);


--
-- Name: TABLE t_novel_agent_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_novel_agent_message IS '小说助手会话消息：USER / ASSISTANT，按 id 升序即时间序';


--
-- Name: COLUMN t_novel_agent_message.role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_agent_message.role IS '消息角色：USER 用户 / ASSISTANT AI / SYSTEM 系统';


--
-- Name: COLUMN t_novel_agent_message.operations; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_agent_message.operations IS '助手消息执行的操作摘要 JSON 数组，用户消息为空';


--
-- Name: COLUMN t_novel_agent_message.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_agent_message.status IS 'OK 正常 / FAILED AI 调用失败（不进入上下文）';


--
-- Name: COLUMN t_novel_agent_message.error_message; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_agent_message.error_message IS '调用失败原因，仅 FAILED 时有值';


--
-- Name: t_novel_agent_session; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_novel_agent_session (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    title character varying(120),
    message_count integer DEFAULT 0 NOT NULL,
    last_message_at timestamp without time zone,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_session_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_novel_agent_session; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_novel_agent_session IS '小说智能助手会话：每部小说至多一条，首次进入自动创建';


--
-- Name: COLUMN t_novel_agent_session.title; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_agent_session.title IS '会话标题，取首条用户消息前若干字';


--
-- Name: COLUMN t_novel_agent_session.message_count; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_agent_session.message_count IS '当前上下文消息条数';


--
-- Name: COLUMN t_novel_agent_session.last_message_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_agent_session.last_message_at IS '最后一条消息时间';


--
-- Name: t_novel_cover; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_novel_cover (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    file_path character varying(300) NOT NULL,
    mime_type character varying(32) DEFAULT 'image/jpeg'::character varying NOT NULL,
    file_size integer,
    width integer,
    height integer,
    prompt text,
    negative_prompt character varying(500),
    provider character varying(32),
    model character varying(120),
    is_primary smallint DEFAULT 0 NOT NULL,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    style character varying(32),
    has_text smallint DEFAULT 0 NOT NULL,
    title_text character varying(120),
    author_text character varying(80),
    text_mode character varying(16) DEFAULT 'OVERLAY'::character varying NOT NULL,
    CONSTRAINT ck_novel_cover_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_novel_cover_primary CHECK ((is_primary = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_novel_cover_sort CHECK (((sort_no >= 0) AND (sort_no <= 4)))
);


--
-- Name: TABLE t_novel_cover; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_novel_cover IS '小说封面：一部小说最多 5 张，书架卡片可左右切换';


--
-- Name: COLUMN t_novel_cover.file_path; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.file_path IS '相对封面根目录的路径，规则 user_id/novel_id/{uuid}.jpg';


--
-- Name: COLUMN t_novel_cover.mime_type; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.mime_type IS '实际图片类型：image/jpeg / image/png（少数平台返回 webp）';


--
-- Name: COLUMN t_novel_cover.prompt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.prompt IS '生成时使用的正向提示词（用户输入或系统拼装，便于复现）';


--
-- Name: COLUMN t_novel_cover.negative_prompt; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.negative_prompt IS '生成时使用的负面提示词，平台不支持时为空';


--
-- Name: COLUMN t_novel_cover.provider; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.provider IS '生成时使用的平台协议';


--
-- Name: COLUMN t_novel_cover.model; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.model IS '生成时实际使用的模型名';


--
-- Name: COLUMN t_novel_cover.is_primary; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.is_primary IS '是否书架默认展示的那一张：1 是 / 0 否，每部小说至多一张';


--
-- Name: COLUMN t_novel_cover.sort_no; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_novel_cover.sort_no IS '书架左右切换的顺序，0~4，删除后重排保证连续';


--
-- Name: t_prompt_template; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_prompt_template (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene character varying(32) NOT NULL,
    content text NOT NULL,
    revision integer DEFAULT 1 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_prompt_template_deleted CHECK ((deleted = ANY (ARRAY[0, 1])))
);


--
-- Name: TABLE t_prompt_template; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_prompt_template IS '用户级 AI 提示词模板覆盖：只存可编辑的指令段';


--
-- Name: COLUMN t_prompt_template.scene; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_prompt_template.scene IS 'AiScene 编码，如 BODY_GEN / OUTLINE_GEN';


--
-- Name: COLUMN t_prompt_template.content; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_prompt_template.content IS '用户模板正文，支持 {{变量}} 占位';


--
-- Name: t_scene_binding; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_scene_binding (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene_code character varying(64) NOT NULL,
    ai_config_id bigint NOT NULL,
    model character varying(128),
    temperature numeric(3,2),
    max_tokens integer,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_scene_binding_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_scene_binding_max_tokens CHECK (((max_tokens IS NULL) OR ((max_tokens >= 1) AND (max_tokens <= 200000)))),
    CONSTRAINT ck_scene_binding_temperature CHECK (((temperature IS NULL) OR ((temperature >= (0)::numeric) AND (temperature <= (2)::numeric))))
);


--
-- Name: TABLE t_scene_binding; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_scene_binding IS '场景级模型绑定：未绑定场景回落到默认端点与其默认参数';


--
-- Name: COLUMN t_scene_binding.scene_code; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_scene_binding.scene_code IS 'AI 场景编码，取值见 com.storyforge.modules.ai.scene.AiScene（已实现场景，不再保留占位项）';


--
-- Name: COLUMN t_scene_binding.temperature; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_scene_binding.temperature IS '为空表示沿用端点默认温度';


--
-- Name: COLUMN t_scene_binding.max_tokens; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_scene_binding.max_tokens IS '为空表示沿用端点默认 max_tokens';


--
-- Name: t_search_config; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_search_config (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    name character varying(100) NOT NULL,
    provider character varying(32) NOT NULL,
    base_url character varying(500),
    api_key_cipher text,
    api_key_mask character varying(64),
    default_top_k integer DEFAULT 6 NOT NULL,
    language character varying(32) DEFAULT 'zh-CN'::character varying NOT NULL,
    region character varying(32),
    timeout_ms integer DEFAULT 15000 NOT NULL,
    enabled boolean DEFAULT true NOT NULL,
    is_default boolean DEFAULT false NOT NULL,
    remark character varying(500),
    last_test_at timestamp without time zone,
    last_test_ok boolean,
    last_test_latency_ms integer,
    last_test_message character varying(500),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_search_config; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_search_config IS '联网搜索源配置（按用户隔离，API Key 加密存储）';


--
-- Name: COLUMN t_search_config.provider; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_search_config.provider IS 'BING / DUCKDUCKGO / WIKIPEDIA / SEARXNG / TAVILY / SERPER';


--
-- Name: t_search_scene; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_search_scene (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene_code character varying(64) NOT NULL,
    config_id bigint,
    enabled boolean DEFAULT true NOT NULL,
    top_k integer,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT now() NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT now() NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL
);


--
-- Name: TABLE t_search_scene; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_search_scene IS '联网搜索场景绑定：事件生成 / 大纲生成 / 正文生成';


--
-- Name: COLUMN t_search_scene.config_id; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_search_scene.config_id IS '为空表示继承默认搜索源';


--
-- Name: t_setting_doc; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_setting_doc (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    category character varying(32) NOT NULL,
    title character varying(120) NOT NULL,
    content_md text,
    inject_scope character varying(16) DEFAULT 'GLOBAL'::character varying NOT NULL,
    sort integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    CONSTRAINT ck_setting_doc_category CHECK (((category)::text = ANY ((ARRAY['WORLDVIEW'::character varying, 'BACKGROUND'::character varying, 'CORE_CONFLICT'::character varying, 'HIGHLIGHT'::character varying, 'STYLE'::character varying])::text[]))),
    CONSTRAINT ck_setting_doc_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_setting_doc_scope CHECK (((inject_scope)::text = ANY ((ARRAY['GLOBAL'::character varying, 'CHAPTER'::character varying, 'MANUAL'::character varying])::text[])))
);


--
-- Name: TABLE t_setting_doc; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_setting_doc IS '小说设定文档：一部小说每个分类一份，供 AI 生成时注入上下文';


--
-- Name: COLUMN t_setting_doc.category; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_setting_doc.category IS '设定分类：WORLDVIEW 世界观 / BACKGROUND 背景 / CORE_CONFLICT 核心冲突 / HIGHLIGHT 核心看点 / STYLE 文风基调';


--
-- Name: COLUMN t_setting_doc.content_md; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_setting_doc.content_md IS '设定正文（纯文本，多段用换行分隔；HIGHLIGHT 每行一条）';


--
-- Name: COLUMN t_setting_doc.inject_scope; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_setting_doc.inject_scope IS '注入范围：GLOBAL 全局 / CHAPTER 章节 / MANUAL 仅手动引用';


--
-- Name: t_timeline_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_timeline_event (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    event_time character varying(64),
    title character varying(200) NOT NULL,
    description text,
    sort_no integer DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'UNUSED'::character varying NOT NULL,
    CONSTRAINT ck_timeline_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_timeline_status CHECK (((status)::text = ANY ((ARRAY['UNUSED'::character varying, 'ONGOING'::character varying, 'DONE'::character varying, 'ABANDONED'::character varying])::text[])))
);


--
-- Name: TABLE t_timeline_event; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_timeline_event IS '小说时间线事件：按故事内时间排序，供 AI 写作时对齐前后因果';


--
-- Name: COLUMN t_timeline_event.event_time; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_timeline_event.event_time IS '故事内时间描述，如「第三年春」「主角十八岁」';


--
-- Name: COLUMN t_timeline_event.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_timeline_event.status IS '事件状态：UNUSED 未使用 / ONGOING 进行中 / DONE 已完成 / ABANDONED 废弃';


--
-- Name: t_token_blacklist; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_token_blacklist (
    id bigint NOT NULL,
    jti character varying(64) NOT NULL,
    user_id bigint,
    token_type character varying(16) NOT NULL,
    expires_at timestamp without time zone NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT ck_token_blacklist_type CHECK (((token_type)::text = ANY ((ARRAY['access'::character varying, 'refresh'::character varying])::text[])))
);


--
-- Name: TABLE t_token_blacklist; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_token_blacklist IS '已失效令牌（登出 / 刷新轮换），无 Redis 场景下用 PG 承载';


--
-- Name: COLUMN t_token_blacklist.jti; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_token_blacklist.jti IS '令牌唯一标识';


--
-- Name: COLUMN t_token_blacklist.expires_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_token_blacklist.expires_at IS '令牌自身过期时间，用于定时清理';


--
-- Name: t_user; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.t_user (
    id bigint NOT NULL,
    username character varying(64) NOT NULL,
    password_hash character varying(100) NOT NULL,
    nickname character varying(64),
    role character varying(32) DEFAULT 'USER'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    must_change_password boolean DEFAULT false NOT NULL,
    remark character varying(255),
    last_login_at timestamp without time zone,
    last_login_ip character varying(64),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    created_by bigint,
    updated_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_by bigint,
    version integer DEFAULT 0 NOT NULL,
    prev_login_at timestamp without time zone,
    prev_login_ip character varying(64),
    CONSTRAINT ck_user_deleted CHECK ((deleted = ANY (ARRAY[0, 1]))),
    CONSTRAINT ck_user_role CHECK (((role)::text = ANY ((ARRAY['SUPER_ADMIN'::character varying, 'USER'::character varying])::text[]))),
    CONSTRAINT ck_user_status CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'DISABLED'::character varying])::text[])))
);


--
-- Name: TABLE t_user; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON TABLE public.t_user IS '用户（平台唯一账号形态，最高数据隔离单元）';


--
-- Name: COLUMN t_user.username; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.username IS '登录名，未删除记录内唯一';


--
-- Name: COLUMN t_user.password_hash; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.password_hash IS 'BCrypt 口令散列';


--
-- Name: COLUMN t_user.role; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.role IS 'SUPER_ADMIN 超管 / USER 普通用户';


--
-- Name: COLUMN t_user.status; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.status IS 'ACTIVE 启用 / DISABLED 停用';


--
-- Name: COLUMN t_user.must_change_password; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.must_change_password IS '为 true 时登录后仅可访问改密接口';


--
-- Name: COLUMN t_user.deleted; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.deleted IS '逻辑删除：0 未删除 / 1 已删除（保留 30 天可恢复）';


--
-- Name: COLUMN t_user.version; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.version IS '乐观锁版本';


--
-- Name: COLUMN t_user.prev_login_at; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.prev_login_at IS '上一次登录时间：登录成功时把原 last_login_at 挪过来';


--
-- Name: COLUMN t_user.prev_login_ip; Type: COMMENT; Schema: public; Owner: -
--

COMMENT ON COLUMN public.t_user.prev_login_ip IS '上一次登录 IP';


--
-- Name: t_ai_config pk_ai_config; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ai_config
    ADD CONSTRAINT pk_ai_config PRIMARY KEY (id);


--
-- Name: t_ai_usage pk_ai_usage; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_ai_usage
    ADD CONSTRAINT pk_ai_usage PRIMARY KEY (id);


--
-- Name: t_audit_log pk_audit_log; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_audit_log
    ADD CONSTRAINT pk_audit_log PRIMARY KEY (id);


--
-- Name: t_change_history pk_change_history; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_change_history
    ADD CONSTRAINT pk_change_history PRIMARY KEY (id);


--
-- Name: t_chapter pk_chapter; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_chapter
    ADD CONSTRAINT pk_chapter PRIMARY KEY (id);


--
-- Name: t_chapter_analysis pk_chapter_analysis; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_chapter_analysis
    ADD CONSTRAINT pk_chapter_analysis PRIMARY KEY (id);


--
-- Name: t_chapter_character pk_chapter_character; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_chapter_character
    ADD CONSTRAINT pk_chapter_character PRIMARY KEY (id);


--
-- Name: t_chapter_event pk_chapter_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_chapter_event
    ADD CONSTRAINT pk_chapter_event PRIMARY KEY (id);


--
-- Name: t_character pk_character; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_character
    ADD CONSTRAINT pk_character PRIMARY KEY (id);


--
-- Name: t_character_attribute pk_character_attribute; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_character_attribute
    ADD CONSTRAINT pk_character_attribute PRIMARY KEY (id);


--
-- Name: t_character_inventory pk_character_inventory; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_character_inventory
    ADD CONSTRAINT pk_character_inventory PRIMARY KEY (id);


--
-- Name: t_character_relation pk_character_relation; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_character_relation
    ADD CONSTRAINT pk_character_relation PRIMARY KEY (id);


--
-- Name: t_event_participant pk_event_participant; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_event_participant
    ADD CONSTRAINT pk_event_participant PRIMARY KEY (id);


--
-- Name: t_faction pk_faction; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_faction
    ADD CONSTRAINT pk_faction PRIMARY KEY (id);


--
-- Name: t_faction_member pk_faction_member; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_faction_member
    ADD CONSTRAINT pk_faction_member PRIMARY KEY (id);


--
-- Name: t_inspiration_message pk_inspiration_message; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_inspiration_message
    ADD CONSTRAINT pk_inspiration_message PRIMARY KEY (id);


--
-- Name: t_inspiration_session pk_inspiration_session; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_inspiration_session
    ADD CONSTRAINT pk_inspiration_session PRIMARY KEY (id);


--
-- Name: t_inspiration_skill pk_inspiration_skill; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_inspiration_skill
    ADD CONSTRAINT pk_inspiration_skill PRIMARY KEY (id);


--
-- Name: t_item pk_item; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_item
    ADD CONSTRAINT pk_item PRIMARY KEY (id);


--
-- Name: t_master_outline pk_master_outline; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_master_outline
    ADD CONSTRAINT pk_master_outline PRIMARY KEY (id);


--
-- Name: t_novel pk_novel; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_novel
    ADD CONSTRAINT pk_novel PRIMARY KEY (id);


--
-- Name: t_novel_agent_message pk_novel_agent_message; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_novel_agent_message
    ADD CONSTRAINT pk_novel_agent_message PRIMARY KEY (id);


--
-- Name: t_novel_agent_session pk_novel_agent_session; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_novel_agent_session
    ADD CONSTRAINT pk_novel_agent_session PRIMARY KEY (id);


--
-- Name: t_novel_cover pk_novel_cover; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_novel_cover
    ADD CONSTRAINT pk_novel_cover PRIMARY KEY (id);


--
-- Name: t_prompt_template pk_prompt_template; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_prompt_template
    ADD CONSTRAINT pk_prompt_template PRIMARY KEY (id);


--
-- Name: t_scene_binding pk_scene_binding; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_scene_binding
    ADD CONSTRAINT pk_scene_binding PRIMARY KEY (id);


--
-- Name: t_setting_doc pk_setting_doc; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_setting_doc
    ADD CONSTRAINT pk_setting_doc PRIMARY KEY (id);


--
-- Name: t_timeline_event pk_timeline_event; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_timeline_event
    ADD CONSTRAINT pk_timeline_event PRIMARY KEY (id);


--
-- Name: t_token_blacklist pk_token_blacklist; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_token_blacklist
    ADD CONSTRAINT pk_token_blacklist PRIMARY KEY (id);


--
-- Name: t_user pk_user; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_user
    ADD CONSTRAINT pk_user PRIMARY KEY (id);


--
-- Name: t_book_digest t_book_digest_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_book_digest
    ADD CONSTRAINT t_book_digest_pkey PRIMARY KEY (id);


--
-- Name: t_search_config t_search_config_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_search_config
    ADD CONSTRAINT t_search_config_pkey PRIMARY KEY (id);


--
-- Name: t_search_scene t_search_scene_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.t_search_scene
    ADD CONSTRAINT t_search_scene_pkey PRIMARY KEY (id);


--
-- Name: idx_ai_config_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_config_user ON public.t_ai_config USING btree (user_id);


--
-- Name: idx_ai_usage_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_usage_user_created ON public.t_ai_usage USING btree (user_id, created_at DESC);


--
-- Name: idx_ai_usage_user_scene_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ai_usage_user_scene_created ON public.t_ai_usage USING btree (user_id, scene, created_at DESC);


--
-- Name: idx_attribute_character; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_attribute_character ON public.t_character_attribute USING btree (user_id, character_id, sort_no);


--
-- Name: idx_audit_log_action; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_action ON public.t_audit_log USING btree (action);


--
-- Name: idx_audit_log_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_created_at ON public.t_audit_log USING btree (created_at);


--
-- Name: idx_audit_log_user_id; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_audit_log_user_id ON public.t_audit_log USING btree (user_id);


--
-- Name: idx_book_digest_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_book_digest_user_created ON public.t_book_digest USING btree (user_id, created_at DESC);


--
-- Name: idx_book_digest_user_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_book_digest_user_status ON public.t_book_digest USING btree (user_id, status);


--
-- Name: idx_change_history_entity; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_change_history_entity ON public.t_change_history USING btree (entity_type, entity_id);


--
-- Name: idx_change_history_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_change_history_novel ON public.t_change_history USING btree (novel_id, chapter_no);


--
-- Name: idx_chapter_analysis_chapter; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_analysis_chapter ON public.t_chapter_analysis USING btree (chapter_id);


--
-- Name: idx_chapter_analysis_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_analysis_novel ON public.t_chapter_analysis USING btree (novel_id, created_at DESC);


--
-- Name: idx_chapter_character_chapter; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_character_chapter ON public.t_chapter_character USING btree (chapter_id);


--
-- Name: idx_chapter_character_character; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_character_character ON public.t_chapter_character USING btree (character_id);


--
-- Name: idx_chapter_event_chapter; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_event_chapter ON public.t_chapter_event USING btree (chapter_id);


--
-- Name: idx_chapter_event_event; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_event_event ON public.t_chapter_event USING btree (event_id);


--
-- Name: idx_chapter_master_outline; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_master_outline ON public.t_chapter USING btree (master_outline_id);


--
-- Name: idx_chapter_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_novel ON public.t_chapter USING btree (novel_id, sort_no);


--
-- Name: idx_chapter_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_chapter_user ON public.t_chapter USING btree (user_id);


--
-- Name: idx_character_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_character_novel ON public.t_character USING btree (novel_id, sort_no);


--
-- Name: idx_character_relation_from; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_character_relation_from ON public.t_character_relation USING btree (from_character_id);


--
-- Name: idx_character_relation_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_character_relation_novel ON public.t_character_relation USING btree (novel_id, sort_no);


--
-- Name: idx_character_relation_to; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_character_relation_to ON public.t_character_relation USING btree (to_character_id);


--
-- Name: idx_character_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_character_user ON public.t_character USING btree (user_id);


--
-- Name: idx_event_participant_character; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_event_participant_character ON public.t_event_participant USING btree (character_id);


--
-- Name: idx_event_participant_event; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_event_participant_event ON public.t_event_participant USING btree (event_id);


--
-- Name: idx_faction_member_character; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_faction_member_character ON public.t_faction_member USING btree (character_id);


--
-- Name: idx_faction_member_faction; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_faction_member_faction ON public.t_faction_member USING btree (faction_id);


--
-- Name: idx_faction_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_faction_novel ON public.t_faction USING btree (novel_id, sort_no);


--
-- Name: idx_inspiration_message_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inspiration_message_session ON public.t_inspiration_message USING btree (session_id, id);


--
-- Name: idx_inspiration_message_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inspiration_message_user ON public.t_inspiration_message USING btree (user_id);


--
-- Name: idx_inspiration_skill_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inspiration_skill_user ON public.t_inspiration_skill USING btree (user_id);


--
-- Name: idx_inventory_character; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_inventory_character ON public.t_character_inventory USING btree (user_id, character_id, sort_no);


--
-- Name: idx_item_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_item_novel ON public.t_item USING btree (novel_id, sort_no);


--
-- Name: idx_item_novel_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_item_novel_status ON public.t_item USING btree (novel_id, status) WHERE (deleted = 0);


--
-- Name: idx_master_outline_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_master_outline_novel ON public.t_master_outline USING btree (novel_id, sort_no);


--
-- Name: idx_master_outline_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_master_outline_user ON public.t_master_outline USING btree (user_id);


--
-- Name: idx_novel_agent_message_session; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novel_agent_message_session ON public.t_novel_agent_message USING btree (session_id, id);


--
-- Name: idx_novel_agent_message_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novel_agent_message_user ON public.t_novel_agent_message USING btree (user_id);


--
-- Name: idx_novel_agent_session_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novel_agent_session_user ON public.t_novel_agent_session USING btree (user_id);


--
-- Name: idx_novel_cover_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novel_cover_novel ON public.t_novel_cover USING btree (novel_id, sort_no);


--
-- Name: idx_novel_user_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_novel_user_created ON public.t_novel USING btree (user_id, created_at DESC);


--
-- Name: idx_scene_binding_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_scene_binding_user ON public.t_scene_binding USING btree (user_id);


--
-- Name: idx_search_config_user; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_search_config_user ON public.t_search_config USING btree (user_id, created_at) WHERE (deleted = 0);


--
-- Name: idx_setting_doc_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_setting_doc_novel ON public.t_setting_doc USING btree (novel_id, sort);


--
-- Name: idx_timeline_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_timeline_novel ON public.t_timeline_event USING btree (novel_id, sort_no);


--
-- Name: idx_timeline_novel_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_timeline_novel_status ON public.t_timeline_event USING btree (novel_id, status, sort_no);


--
-- Name: idx_token_blacklist_expires_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_token_blacklist_expires_at ON public.t_token_blacklist USING btree (expires_at);


--
-- Name: idx_user_created_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_created_at ON public.t_user USING btree (created_at);


--
-- Name: idx_user_role_status; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_user_role_status ON public.t_user USING btree (role, status);


--
-- Name: uk_ai_config_default_kind; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_ai_config_default_kind ON public.t_ai_config USING btree (user_id, kind) WHERE ((is_default = true) AND (deleted = 0));


--
-- Name: uk_ai_config_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_ai_config_name ON public.t_ai_config USING btree (user_id, name) WHERE (deleted = 0);


--
-- Name: uk_attribute_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_attribute_name ON public.t_character_attribute USING btree (user_id, character_id, name) WHERE (deleted = 0);


--
-- Name: uk_change_history_entry; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_change_history_entry ON public.t_change_history USING btree (user_id, chapter_id, entity_type, entity_id, field_key) WHERE (deleted = 0);


--
-- Name: uk_chapter_character; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_chapter_character ON public.t_chapter_character USING btree (user_id, chapter_id, character_id) WHERE (deleted = 0);


--
-- Name: uk_chapter_event; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_chapter_event ON public.t_chapter_event USING btree (user_id, chapter_id, event_id) WHERE (deleted = 0);


--
-- Name: uk_character_relation_pair; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_character_relation_pair ON public.t_character_relation USING btree (user_id, novel_id, from_character_id, to_character_id, relation_type) WHERE (deleted = 0);


--
-- Name: uk_event_participant; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_event_participant ON public.t_event_participant USING btree (user_id, event_id, character_id) WHERE (deleted = 0);


--
-- Name: uk_faction_member; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_faction_member ON public.t_faction_member USING btree (user_id, faction_id, character_id) WHERE (deleted = 0);


--
-- Name: uk_inspiration_session_user; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_inspiration_session_user ON public.t_inspiration_session USING btree (user_id) WHERE (deleted = 0);


--
-- Name: uk_inspiration_skill_code; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_inspiration_skill_code ON public.t_inspiration_skill USING btree (user_id, code) WHERE (deleted = 0);


--
-- Name: uk_inventory_item; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_inventory_item ON public.t_character_inventory USING btree (user_id, character_id, item_id) WHERE ((deleted = 0) AND (item_id IS NOT NULL));


--
-- Name: uk_inventory_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_inventory_name ON public.t_character_inventory USING btree (user_id, character_id, name) WHERE ((deleted = 0) AND (item_id IS NULL));


--
-- Name: uk_novel_agent_session_novel; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_novel_agent_session_novel ON public.t_novel_agent_session USING btree (novel_id) WHERE (deleted = 0);


--
-- Name: uk_novel_cover_primary; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_novel_cover_primary ON public.t_novel_cover USING btree (user_id, novel_id) WHERE ((is_primary = 1) AND (deleted = 0));


--
-- Name: uk_novel_cover_sort; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_novel_cover_sort ON public.t_novel_cover USING btree (user_id, novel_id, sort_no) WHERE (deleted = 0);


--
-- Name: uk_novel_title; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_novel_title ON public.t_novel USING btree (user_id, title) WHERE (deleted = 0);


--
-- Name: uk_prompt_template_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_prompt_template_scene ON public.t_prompt_template USING btree (user_id, scene) WHERE (deleted = 0);


--
-- Name: uk_scene_binding; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_scene_binding ON public.t_scene_binding USING btree (user_id, scene_code) WHERE (deleted = 0);


--
-- Name: uk_search_config_default; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_search_config_default ON public.t_search_config USING btree (user_id) WHERE ((deleted = 0) AND (is_default = true) AND (enabled = true));


--
-- Name: uk_search_config_name; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_search_config_name ON public.t_search_config USING btree (user_id, name) WHERE (deleted = 0);


--
-- Name: uk_search_scene; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_search_scene ON public.t_search_scene USING btree (user_id, scene_code) WHERE (deleted = 0);


--
-- Name: uk_setting_doc_novel_category; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_setting_doc_novel_category ON public.t_setting_doc USING btree (user_id, novel_id, category) WHERE (deleted = 0);


--
-- Name: uk_token_blacklist_jti; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_token_blacklist_jti ON public.t_token_blacklist USING btree (jti);


--
-- Name: uk_user_username; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uk_user_username ON public.t_user USING btree (username) WHERE (deleted = 0);


--
-- PostgreSQL database dump complete
--


