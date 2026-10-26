-- =============================================================================
-- StoryForge 数据库基线（V1 合并版 · MySQL）
--
-- 本文件由 script/db/gen-baseline.mjs 从 postgresql/V1__baseline.sql 派生，
-- 禁止手工修改：结构变更请改 PostgreSQL 版并重跑脚本，三份脚本始终同源。
--
-- 目标版本：MySQL 8.0.13+ / MariaDB 10.2+
--
-- 与 PostgreSQL 版有意保留的差异：
--   1. 逻辑删除的部分唯一索引（WHERE deleted = 0）改用「条件函数列」等效实现：
--      条件成立时表达式取 0 参与唯一性，不成立时取 NULL（唯一索引允许 NULL 重复），
--      因此「只有未删除的行受唯一约束」的语义与 PostgreSQL 完全一致。
--      MySQL 的函数索引要求 8.0.13+。
--   2. 部分普通索引（非唯一）直接丢弃 WHERE，仅索引体积略大，查询计划不受影响。
-- =============================================================================


SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ---------------------------- t_ai_config ----------------------------
CREATE TABLE t_ai_config (
    id bigint NOT NULL,
    user_id bigint NOT NULL COMMENT '归属用户，数据隔离键',
    name varchar(64) NOT NULL,
    base_url varchar(500) NOT NULL COMMENT '接口基址，需含版本段，如 https://api.openai.com/v1',
    api_key_cipher text COMMENT 'API Key 密文（AES-256-GCM），禁止明文',
    api_key_mask varchar(64) COMMENT 'API Key 掩码，仅用于界面回显',
    default_model varchar(128),
    model_list text COMMENT '最近一次从 /v1/models 拉取的模型列表缓存（JSON 数组文本）',
    timeout_ms int DEFAULT 60000 NOT NULL,
    temperature decimal(3,2) DEFAULT 0.80 NOT NULL,
    max_tokens int DEFAULT 4096 NOT NULL,
    top_p decimal(3,2) DEFAULT 1.00 NOT NULL,
    extra_headers text COMMENT '附加请求头 JSON 对象文本，如 {"X-Org":"xxx"}',
    enabled tinyint(1) DEFAULT 1 NOT NULL,
    is_default tinyint(1) DEFAULT 0 NOT NULL COMMENT '该用户该类型（kind）下的默认端点，每用户每类型至多一个',
    remark varchar(255),
    last_test_at datetime(3),
    last_test_ok tinyint(1) COMMENT '最近一次连通性测试结果',
    last_test_latency_ms int,
    deleted smallint DEFAULT 0 NOT NULL COMMENT '逻辑删除：0 未删除 / 1 已删除',
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    kind varchar(16) DEFAULT 'TEXT' NOT NULL COMMENT '接口类型：TEXT 文本 / IMAGE 图像（文生图），创建后不可变更',
    provider varchar(32) DEFAULT 'OPENAI' NOT NULL COMMENT '平台协议：OPENAI / SILICONFLOW / VOLCENGINE / ZHIPU / DASHSCOPE / MODELSCOPE',
    image_size varchar(32) COMMENT '图像接口默认出图尺寸，形如 1024x1536；文本接口为空',
    CONSTRAINT ck_ai_config_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_ai_config_kind CHECK ((kind IN ('TEXT', 'IMAGE'))),
    CONSTRAINT ck_ai_config_max_tokens CHECK (((max_tokens >= 1) AND (max_tokens <= 200000))),
    CONSTRAINT ck_ai_config_temperature CHECK (((temperature >= (0)) AND (temperature <= (2)))),
    CONSTRAINT ck_ai_config_timeout CHECK (((timeout_ms >= 1000) AND (timeout_ms <= 600000))),
    CONSTRAINT ck_ai_config_top_p CHECK (((top_p >= (0)) AND (top_p <= (1))))
) COMMENT='用户级 AI 接口配置（OpenAI 协议）';

ALTER TABLE t_ai_config ADD CONSTRAINT pk_ai_config PRIMARY KEY (id);

-- ---------------------------- t_ai_usage ----------------------------
CREATE TABLE t_ai_usage (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene varchar(32) NOT NULL COMMENT 'AiScene 编码，如 BODY_GEN / OUTLINE_GEN',
    model varchar(120) COMMENT '服务端实际使用的模型名',
    prompt_tokens int DEFAULT 0 NOT NULL COMMENT '输入 token 数（服务端未返回时记 0）',
    completion_tokens int DEFAULT 0 NOT NULL COMMENT '输出 token 数（服务端未返回时记 0）',
    total_tokens int DEFAULT 0 NOT NULL COMMENT '输入 + 输出 token 数',
    latency_ms int COMMENT '端到端耗时（毫秒）',
    success smallint DEFAULT 1 NOT NULL COMMENT '1 成功 / 0 失败（失败时 token 可能为 0）',
    error_message varchar(500) COMMENT '失败原因，成功时为空',
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    CONSTRAINT ck_ai_usage_success CHECK ((success IN (0, 1))),
    CONSTRAINT ck_ai_usage_tokens CHECK (((prompt_tokens >= 0) AND (completion_tokens >= 0) AND (total_tokens >= 0)))
) COMMENT='AI 调用用量流水：每次模型调用一行，供仪表盘统计 token 消耗';

ALTER TABLE t_ai_usage ADD CONSTRAINT pk_ai_usage PRIMARY KEY (id);

-- ---------------------------- t_audit_log ----------------------------
CREATE TABLE t_audit_log (
    id bigint NOT NULL,
    user_id bigint,
    username varchar(64),
    action varchar(64) NOT NULL COMMENT '动作编码，如 USER_CREATE / USER_DISABLE',
    target_type varchar(64),
    target_id varchar(64),
    detail varchar(2000) COMMENT '脱敏摘要，禁止记录口令与令牌',
    ip varchar(64),
    result varchar(16) NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    CONSTRAINT ck_audit_log_result CHECK ((result IN ('SUCCESS', 'FAIL')))
) COMMENT='关键写操作审计日志（谁、何时、改了什么）';

ALTER TABLE t_audit_log ADD CONSTRAINT pk_audit_log PRIMARY KEY (id);

-- ---------------------------- t_book_digest ----------------------------
CREATE TABLE t_book_digest (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    source_name varchar(200),
    source_chars int DEFAULT 0 NOT NULL,
    source_chapters int DEFAULT 0 NOT NULL,
    adaptation_level varchar(16) DEFAULT 'MEDIUM' NOT NULL COMMENT '改编程度：LIGHT 轻度 / MEDIUM 中度 / HEAVY 重度 / REBUILD 完全重构',
    background_direction varchar(500) COMMENT '背景改编方向（用户填写）',
    chapter_target int DEFAULT 20 NOT NULL,
    status varchar(16) DEFAULT 'RUNNING' NOT NULL,
    percent int DEFAULT 0 NOT NULL,
    step_no int DEFAULT 0 NOT NULL,
    step_total int DEFAULT 6 NOT NULL,
    step_label varchar(64),
    result_json text COMMENT '改编结果 JSON：主线脉络 + 设定 + 人物/关系/势力 + 大纲/事件',
    result_title varchar(200),
    error_message varchar(500),
    generated_novel_id bigint,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_book_digest_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_book_digest_level CHECK ((adaptation_level IN ('LIGHT', 'MEDIUM', 'HEAVY', 'REBUILD'))),
    CONSTRAINT ck_book_digest_status CHECK ((status IN ('RUNNING', 'SUCCEEDED', 'FAILED')))
) COMMENT='拆书记录：原文分析、改编结果与生成的成书';

ALTER TABLE t_book_digest ADD CONSTRAINT t_book_digest_pkey PRIMARY KEY (id);

-- ---------------------------- t_change_history ----------------------------
CREATE TABLE t_change_history (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL COMMENT '产生这次变更的章节',
    chapter_no int,
    chapter_title varchar(200),
    analysis_id bigint,
    entity_type varchar(32) NOT NULL COMMENT 'CHARACTER / ITEM / FACTION / CHARACTER_ATTRIBUTE / INVENTORY',
    entity_id bigint NOT NULL,
    entity_name varchar(128) NOT NULL,
    field_key varchar(100) NOT NULL COMMENT '变更字段：lifeStatus / identity / owner / status / quantity / scope 等',
    field_label varchar(128),
    before_state varchar(500) COMMENT '原状态（取自分析前的库内值，取不到时为「—」）',
    after_state varchar(500) COMMENT '现状态（本章结束时的状态）',
    reason varchar(500),
    evidence varchar(500) COMMENT '正文依据，模型摘录的原句',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_change_history_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_change_history_entity_type CHECK ((entity_type IN ('CHARACTER', 'ITEM', 'FACTION', 'CHARACTER_ATTRIBUTE', 'INVENTORY')))
) COMMENT='要素变更履历：人物 / 物品 / 势力 / 属性 / 背包的状态变迁';

ALTER TABLE t_change_history ADD CONSTRAINT pk_change_history PRIMARY KEY (id);

-- ---------------------------- t_chapter ----------------------------
CREATE TABLE t_chapter (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    volume_no int DEFAULT 1 NOT NULL COMMENT '所属卷序号，从 1 开始',
    volume_title varchar(120) COMMENT '卷名，如「第一卷 旧书」',
    chapter_no int DEFAULT 1 NOT NULL COMMENT '章节号，同一部小说内递增',
    title varchar(200) NOT NULL,
    summary text COMMENT '大纲摘要：本章要写什么',
    content text COMMENT '正文内容（纯文本）',
    word_count int DEFAULT 0 NOT NULL COMMENT '正文字数（去空白后的字符数）',
    status varchar(16) DEFAULT 'DRAFT' NOT NULL COMMENT 'DRAFT 草稿 / WRITING 写作中 / DONE 已完成',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    master_outline_id bigint COMMENT '关联的总纲 id：本章大纲属于哪一段剧情走向，可空',
    CONSTRAINT ck_chapter_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_chapter_status CHECK ((status IN ('DRAFT', 'WRITING', 'DONE')))
) COMMENT='小说章节：大纲阶段维护摘要，正文阶段维护内容';

ALTER TABLE t_chapter ADD CONSTRAINT pk_chapter PRIMARY KEY (id);

-- ---------------------------- t_chapter_analysis ----------------------------
CREATE TABLE t_chapter_analysis (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    chapter_no int,
    chapter_title varchar(200),
    word_count int DEFAULT 0 NOT NULL,
    target_words int DEFAULT 0 NOT NULL,
    word_pass smallint DEFAULT 0 NOT NULL COMMENT '字数是否达标：1 达标 0 不达标',
    outline_score int DEFAULT 0 NOT NULL COMMENT '与本章大纲的贴合度评分，0~100',
    ai_score int DEFAULT 0 NOT NULL COMMENT 'AI 率评分，0~100，0 为纯 AI 味',
    model varchar(128),
    latency_ms bigint DEFAULT 0 NOT NULL,
    result_json text COMMENT '完整报告 JSON（含评分理由、变更明细、本地检测信号）',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_analysis_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_chapter_analysis_word_pass CHECK ((word_pass IN (0, 1)))
) COMMENT='章节分析报告：字数、大纲贴合度、AI 味评分与抽取到的变更';

ALTER TABLE t_chapter_analysis ADD CONSTRAINT pk_chapter_analysis PRIMARY KEY (id);

-- ---------------------------- t_chapter_character ----------------------------
CREATE TABLE t_chapter_character (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    character_id bigint NOT NULL,
    role_desc varchar(128) COMMENT '在本章中的作用，如「视角人物」「只出现在回忆里」',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_character_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='章节参与人物：章节与角色的多对多关联';

ALTER TABLE t_chapter_character ADD CONSTRAINT pk_chapter_character PRIMARY KEY (id);

-- ---------------------------- t_chapter_event ----------------------------
CREATE TABLE t_chapter_event (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    chapter_id bigint NOT NULL,
    event_id bigint NOT NULL,
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_chapter_event_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='章节关联事件：章节与事件的多对多关联';

ALTER TABLE t_chapter_event ADD CONSTRAINT pk_chapter_event PRIMARY KEY (id);

-- ---------------------------- t_character ----------------------------
CREATE TABLE t_character (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    name varchar(64) NOT NULL,
    alias varchar(128),
    gender varchar(16),
    age_desc varchar(32),
    identity varchar(255) COMMENT '身份职位，如「青云宗外门弟子」',
    role_pos varchar(16) DEFAULT 'SUPPORTING' NOT NULL COMMENT '角色定位：PROTAGONIST 主角 / SUPPORTING 配角 / ANTAGONIST 反派 / MINOR 龙套',
    life_status varchar(16) DEFAULT 'ALIVE' NOT NULL,
    first_chapter int,
    faction_id bigint,
    profile_json text COMMENT '扩展设定 JSON：personality 性格 / motivation 动机 / goldenFinger 金手指 / arc 成长弧线 / appearance 外貌',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_character_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_character_life CHECK ((life_status IN ('ALIVE', 'DEAD', 'MISSING', 'UNKNOWN'))),
    CONSTRAINT ck_character_role CHECK ((role_pos IN ('PROTAGONIST', 'SUPPORTING', 'ANTAGONIST', 'MINOR')))
) COMMENT='小说角色：主角 / 配角 / 反派，设定细节存 profile_json';

ALTER TABLE t_character ADD CONSTRAINT pk_character PRIMARY KEY (id);

-- ---------------------------- t_character_attribute ----------------------------
CREATE TABLE t_character_attribute (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    character_id bigint NOT NULL,
    name varchar(64) NOT NULL,
    value varchar(64),
    unit varchar(16) COMMENT '单位：点 / 元 / 万，可空',
    attr_type varchar(16) DEFAULT 'NUMBER' NOT NULL COMMENT 'NUMBER 数值 / TEXT 文本（等级、境界这类）',
    note varchar(255),
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_attribute_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_attribute_type CHECK ((attr_type IN ('NUMBER', 'TEXT')))
) COMMENT='人物属性：只有当前值，历史变化走 t_change_history';

ALTER TABLE t_character_attribute ADD CONSTRAINT pk_character_attribute PRIMARY KEY (id);

-- ---------------------------- t_character_inventory ----------------------------
CREATE TABLE t_character_inventory (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    character_id bigint NOT NULL,
    entry_type varchar(16) DEFAULT 'MISC' NOT NULL,
    item_id bigint COMMENT 'entry_type=ITEM 时指向 t_item；其余为 NULL',
    name varchar(100) NOT NULL COMMENT 'ITEM 冗余物品名便于展示；CURRENCY / MISC 由用户填写',
    quantity decimal(18,2),
    unit varchar(16),
    status varchar(16) DEFAULT 'HELD' NOT NULL COMMENT 'HELD 持有 / LOST 遗失 / CONSUMED 已消耗',
    note text,
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inventory_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_inventory_entry CHECK ((entry_type IN ('ITEM', 'CURRENCY', 'MISC'))),
    CONSTRAINT ck_inventory_status CHECK ((status IN ('HELD', 'LOST', 'CONSUMED')))
) COMMENT='人物背包：ITEM 关联物品档案，CURRENCY / MISC 为货币与常规物资';

ALTER TABLE t_character_inventory ADD CONSTRAINT pk_character_inventory PRIMARY KEY (id);

-- ---------------------------- t_character_relation ----------------------------
CREATE TABLE t_character_relation (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    from_character_id bigint NOT NULL,
    to_character_id bigint NOT NULL,
    relation_type varchar(32) NOT NULL COMMENT '关系类型：MASTER_APPRENTICE 师徒 / FAMILY 亲属 / SPOUSE 夫妻 / LOVER 恋人 / FRIEND 好友 / ALLY 盟友 / ENEMY 仇敌 / RIVAL 对手 / COLLEAGUE 同门 / OTHER 其他',
    description varchar(255) COMMENT '补充说明，如「韩戍是沈默的师父，当年亲手烧了旧街」',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_character_relation_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_character_relation_self CHECK ((from_character_id <> to_character_id)),
    CONSTRAINT ck_character_relation_type CHECK ((relation_type IN ('MASTER_APPRENTICE', 'FAMILY', 'SPOUSE', 'LOVER', 'FRIEND', 'ALLY', 'ENEMY', 'RIVAL', 'COLLEAGUE', 'OTHER')))
) COMMENT='角色关系：两端 id 已按大小排序，关系语义为对称';

ALTER TABLE t_character_relation ADD CONSTRAINT pk_character_relation PRIMARY KEY (id);

-- ---------------------------- t_event_participant ----------------------------
CREATE TABLE t_event_participant (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    event_id bigint NOT NULL,
    character_id bigint NOT NULL,
    role_desc varchar(64) COMMENT '在本事件中的身份或作用，如「线人」「目击者」',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_event_participant_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='事件参与角色：事件与角色的多对多关联';

ALTER TABLE t_event_participant ADD CONSTRAINT pk_event_participant PRIMARY KEY (id);

-- ---------------------------- t_faction ----------------------------
CREATE TABLE t_faction (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    category varchar(32) COMMENT '势力类型，自由文本，常用值：公司 / 帮派 / 国家 / 组织 / 其他',
    remark varchar(1000),
    scope varchar(500) COMMENT '营业范围或管辖范围',
    status varchar(16) DEFAULT 'ACTIVE' NOT NULL COMMENT '状态：ACTIVE 活跃 / DORMANT 蛰伏 / DESTROYED 覆灭 / DISBANDED 解散',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_faction_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_faction_status CHECK ((status IN ('ACTIVE', 'DORMANT', 'DESTROYED', 'DISBANDED')))
) COMMENT='小说势力：公司 / 帮派 / 国家 / 组织等';

ALTER TABLE t_faction ADD CONSTRAINT pk_faction PRIMARY KEY (id);

-- ---------------------------- t_faction_member ----------------------------
CREATE TABLE t_faction_member (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    faction_id bigint NOT NULL,
    character_id bigint NOT NULL,
    `position` varchar(64) NOT NULL COMMENT '在该势力中的职位，自由文本，如 董事长 / 堂主 / 户部尚书',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_faction_member_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='势力成员：角色在某势力中的职位';

ALTER TABLE t_faction_member ADD CONSTRAINT pk_faction_member PRIMARY KEY (id);

-- ---------------------------- t_inspiration_message ----------------------------
CREATE TABLE t_inspiration_message (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    session_id bigint NOT NULL,
    role varchar(16) NOT NULL COMMENT '消息角色：USER 用户 / ASSISTANT AI / SYSTEM 系统',
    content text,
    model varchar(128),
    prompt_tokens int,
    completion_tokens int,
    status varchar(16) DEFAULT 'OK' NOT NULL COMMENT 'OK 正常 / FAILED AI 调用失败（界面回显错误气泡，不进入上下文）',
    error_message varchar(500) COMMENT '调用失败原因，仅 FAILED 时有值',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_message_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_inspiration_message_role CHECK ((role IN ('USER', 'ASSISTANT', 'SYSTEM'))),
    CONSTRAINT ck_inspiration_message_status CHECK ((status IN ('OK', 'FAILED')))
) COMMENT='灵感会话消息：USER / ASSISTANT，按 id 升序即时间序';

ALTER TABLE t_inspiration_message ADD CONSTRAINT pk_inspiration_message PRIMARY KEY (id);

-- ---------------------------- t_inspiration_session ----------------------------
CREATE TABLE t_inspiration_session (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    title varchar(120) COMMENT '会话标题，取首条用户消息前若干字；清空上下文后重置',
    skill_code varchar(64) DEFAULT 'NOVEL_CREATE' NOT NULL COMMENT '当前选中的技能编码',
    message_count int DEFAULT 0 NOT NULL COMMENT '当前上下文消息条数（清空上下文后归零）',
    last_message_at datetime(3) COMMENT '最后一条消息时间，用于列表排序与展示',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_session_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='灵感模式会话：每个用户至多一条，首次进入时自动创建';

ALTER TABLE t_inspiration_session ADD CONSTRAINT pk_inspiration_session PRIMARY KEY (id);

-- ---------------------------- t_inspiration_skill ----------------------------
CREATE TABLE t_inspiration_skill (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    code varchar(64) NOT NULL COMMENT '技能编码；内置技能固定为 NOVEL_CREATE，自定义技能由系统生成',
    name varchar(64) NOT NULL,
    description varchar(500),
    system_prompt text NOT NULL COMMENT '技能系统提示词，作为每轮对话的 system 消息',
    builtin tinyint(1) DEFAULT 0 NOT NULL COMMENT '是否内置技能：内置技能可改可恢复默认，但不可删除',
    enabled tinyint(1) DEFAULT 1 NOT NULL,
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL COMMENT '逻辑删除：0 未删除 / 1 已删除',
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_inspiration_skill_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='灵感模式技能：系统提示词 + 元信息，按用户隔离';

ALTER TABLE t_inspiration_skill ADD CONSTRAINT pk_inspiration_skill PRIMARY KEY (id);

-- ---------------------------- t_item ----------------------------
CREATE TABLE t_item (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    category varchar(32) COMMENT '类别，如 法宝 / 信物 / 线索 / 消耗品',
    owner varchar(100) COMMENT '当前持有者（角色名或势力）',
    origin text COMMENT '说明：物品来历、经手人、关联的关键事件或秘密',
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    status varchar(16) DEFAULT 'ACTIVE' NOT NULL COMMENT '状态：ACTIVE 活跃 / DESTROYED 摧毁 / ABANDONED 废弃 / LOST 遗失 / CONSUMED 已消耗',
    quantity int COMMENT '数量，可空；唯一或不可计数的物品留空',
    description text COMMENT '物品描述：外貌形态、材质、尺寸、磨损标记、特殊特征与象征意义',
    CONSTRAINT ck_item_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_item_quantity CHECK (((quantity IS NULL) OR (quantity >= 0))),
    CONSTRAINT ck_item_status CHECK ((status IN ('ACTIVE', 'DESTROYED', 'ABANDONED', 'LOST', 'CONSUMED')))
) COMMENT='小说物品道具：法宝 / 信物 / 关键线索等';

ALTER TABLE t_item ADD CONSTRAINT pk_item PRIMARY KEY (id);

-- ---------------------------- t_master_outline ----------------------------
CREATE TABLE t_master_outline (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    sort_no int DEFAULT 0 NOT NULL COMMENT '排序号，从 1 开始递增',
    title varchar(200) NOT NULL COMMENT '总纲标题，如「第一幕 落难与激活」',
    content text COMMENT '总纲正文：这一段故事要发生什么，只写剧情走向，不写人物档案',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_master_outline_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='小说总纲：一段粗粒度剧情走向，指导章节大纲生成';

ALTER TABLE t_master_outline ADD CONSTRAINT pk_master_outline PRIMARY KEY (id);

-- ---------------------------- t_novel ----------------------------
CREATE TABLE t_novel (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    title varchar(200) NOT NULL,
    pen_name varchar(64),
    genre varchar(64),
    tags text COMMENT '标签 JSON 数组文本，如 ["升级流","群像"]',
    intro text COMMENT '简介，来自灵感对话提炼或用户手填',
    cover_path varchar(500),
    status varchar(16) DEFAULT 'DRAFT' NOT NULL COMMENT 'DRAFT 草稿 / WRITING 连载中 / FINISHED 已完结 / ARCHIVED 已归档',
    source varchar(16) DEFAULT 'MANUAL' NOT NULL COMMENT '来源：MANUAL 手动创建 / INSPIRATION 灵感模式一键创建',
    inspiration_session_id bigint COMMENT '来源灵感会话 ID，便于回溯创作过程',
    chapter_count int DEFAULT 0 NOT NULL,
    word_count int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_source CHECK ((source IN ('MANUAL', 'INSPIRATION', 'DECONSTRUCT'))),
    CONSTRAINT ck_novel_status CHECK ((status IN ('DRAFT', 'WRITING', 'FINISHED', 'ARCHIVED')))
) COMMENT='小说主表：数据隔离键为 user_id';

ALTER TABLE t_novel ADD CONSTRAINT pk_novel PRIMARY KEY (id);

-- ---------------------------- t_novel_agent_message ----------------------------
CREATE TABLE t_novel_agent_message (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    session_id bigint NOT NULL,
    role varchar(16) NOT NULL COMMENT '消息角色：USER 用户 / ASSISTANT AI / SYSTEM 系统',
    content text,
    operations text COMMENT '助手消息执行的操作摘要 JSON 数组，用户消息为空',
    model varchar(128),
    prompt_tokens int,
    completion_tokens int,
    status varchar(16) DEFAULT 'OK' NOT NULL COMMENT 'OK 正常 / FAILED AI 调用失败（不进入上下文）',
    error_message varchar(500) COMMENT '调用失败原因，仅 FAILED 时有值',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_message_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_agent_message_role CHECK ((role IN ('USER', 'ASSISTANT', 'SYSTEM'))),
    CONSTRAINT ck_novel_agent_message_status CHECK ((status IN ('OK', 'FAILED')))
) COMMENT='小说助手会话消息：USER / ASSISTANT，按 id 升序即时间序';

ALTER TABLE t_novel_agent_message ADD CONSTRAINT pk_novel_agent_message PRIMARY KEY (id);

-- ---------------------------- t_novel_agent_session ----------------------------
CREATE TABLE t_novel_agent_session (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    title varchar(120) COMMENT '会话标题，取首条用户消息前若干字',
    message_count int DEFAULT 0 NOT NULL COMMENT '当前上下文消息条数',
    last_message_at datetime(3) COMMENT '最后一条消息时间',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_novel_agent_session_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='小说智能助手会话：每部小说至多一条，首次进入自动创建';

ALTER TABLE t_novel_agent_session ADD CONSTRAINT pk_novel_agent_session PRIMARY KEY (id);

-- ---------------------------- t_novel_cover ----------------------------
CREATE TABLE t_novel_cover (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    file_path varchar(300) NOT NULL COMMENT '相对封面根目录的路径，规则 user_id/novel_id/{uuid}.jpg',
    mime_type varchar(32) DEFAULT 'image/jpeg' NOT NULL COMMENT '实际图片类型：image/jpeg / image/png（少数平台返回 webp）',
    file_size int,
    width int,
    height int,
    prompt text COMMENT '生成时使用的正向提示词（用户输入或系统拼装，便于复现）',
    negative_prompt varchar(500) COMMENT '生成时使用的负面提示词，平台不支持时为空',
    provider varchar(32) COMMENT '生成时使用的平台协议',
    model varchar(120) COMMENT '生成时实际使用的模型名',
    is_primary smallint DEFAULT 0 NOT NULL COMMENT '是否书架默认展示的那一张：1 是 / 0 否，每部小说至多一张',
    sort_no int DEFAULT 0 NOT NULL COMMENT '书架左右切换的顺序，0~4，删除后重排保证连续',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    style varchar(32),
    has_text smallint DEFAULT 0 NOT NULL,
    title_text varchar(120),
    author_text varchar(80),
    text_mode varchar(16) DEFAULT 'OVERLAY' NOT NULL,
    CONSTRAINT ck_novel_cover_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_novel_cover_primary CHECK ((is_primary IN (0, 1))),
    CONSTRAINT ck_novel_cover_sort CHECK (((sort_no >= 0) AND (sort_no <= 4)))
) COMMENT='小说封面：一部小说最多 5 张，书架卡片可左右切换';

ALTER TABLE t_novel_cover ADD CONSTRAINT pk_novel_cover PRIMARY KEY (id);

-- ---------------------------- t_prompt_template ----------------------------
CREATE TABLE t_prompt_template (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene varchar(32) NOT NULL COMMENT 'AiScene 编码，如 BODY_GEN / OUTLINE_GEN',
    content text NOT NULL COMMENT '用户模板正文，支持 {{变量}} 占位',
    revision int DEFAULT 1 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_prompt_template_deleted CHECK ((deleted IN (0, 1)))
) COMMENT='用户级 AI 提示词模板覆盖：只存可编辑的指令段';

ALTER TABLE t_prompt_template ADD CONSTRAINT pk_prompt_template PRIMARY KEY (id);

-- ---------------------------- t_scene_binding ----------------------------
CREATE TABLE t_scene_binding (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene_code varchar(64) NOT NULL COMMENT 'AI 场景编码，取值见 com.storyforge.modules.ai.scene.AiScene（已实现场景，不再保留占位项）',
    ai_config_id bigint NOT NULL,
    model varchar(128),
    temperature decimal(3,2) COMMENT '为空表示沿用端点默认温度',
    max_tokens int COMMENT '为空表示沿用端点默认 max_tokens',
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_scene_binding_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_scene_binding_max_tokens CHECK (((max_tokens IS NULL) OR ((max_tokens >= 1) AND (max_tokens <= 200000)))),
    CONSTRAINT ck_scene_binding_temperature CHECK (((temperature IS NULL) OR ((temperature >= (0)) AND (temperature <= (2)))))
) COMMENT='场景级模型绑定：未绑定场景回落到默认端点与其默认参数';

ALTER TABLE t_scene_binding ADD CONSTRAINT pk_scene_binding PRIMARY KEY (id);

-- ---------------------------- t_search_config ----------------------------
CREATE TABLE t_search_config (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    name varchar(100) NOT NULL,
    provider varchar(32) NOT NULL COMMENT 'BING / DUCKDUCKGO / WIKIPEDIA / SEARXNG / TAVILY / SERPER',
    base_url varchar(500),
    api_key_cipher text,
    api_key_mask varchar(64),
    default_top_k int DEFAULT 6 NOT NULL,
    language varchar(32) DEFAULT 'zh-CN' NOT NULL,
    region varchar(32),
    timeout_ms int DEFAULT 15000 NOT NULL,
    enabled tinyint(1) DEFAULT 1 NOT NULL,
    is_default tinyint(1) DEFAULT 0 NOT NULL,
    remark varchar(500),
    last_test_at datetime(3),
    last_test_ok tinyint(1),
    last_test_latency_ms int,
    last_test_message varchar(500),
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL
) COMMENT='联网搜索源配置（按用户隔离，API Key 加密存储）';

ALTER TABLE t_search_config ADD CONSTRAINT t_search_config_pkey PRIMARY KEY (id);

-- ---------------------------- t_search_scene ----------------------------
CREATE TABLE t_search_scene (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    scene_code varchar(64) NOT NULL,
    config_id bigint COMMENT '为空表示继承默认搜索源',
    enabled tinyint(1) DEFAULT 1 NOT NULL,
    top_k int,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL
) COMMENT='联网搜索场景绑定：事件生成 / 大纲生成 / 正文生成';

ALTER TABLE t_search_scene ADD CONSTRAINT t_search_scene_pkey PRIMARY KEY (id);

-- ---------------------------- t_setting_doc ----------------------------
CREATE TABLE t_setting_doc (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    category varchar(32) NOT NULL COMMENT '设定分类：WORLDVIEW 世界观 / BACKGROUND 背景 / CORE_CONFLICT 核心冲突 / HIGHLIGHT 核心看点 / STYLE 文风基调',
    title varchar(120) NOT NULL,
    content_md text COMMENT '设定正文（纯文本，多段用换行分隔；HIGHLIGHT 每行一条）',
    inject_scope varchar(16) DEFAULT 'GLOBAL' NOT NULL COMMENT '注入范围：GLOBAL 全局 / CHAPTER 章节 / MANUAL 仅手动引用',
    sort int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    CONSTRAINT ck_setting_doc_category CHECK ((category IN ('WORLDVIEW', 'BACKGROUND', 'CORE_CONFLICT', 'HIGHLIGHT', 'STYLE'))),
    CONSTRAINT ck_setting_doc_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_setting_doc_scope CHECK ((inject_scope IN ('GLOBAL', 'CHAPTER', 'MANUAL')))
) COMMENT='小说设定文档：一部小说每个分类一份，供 AI 生成时注入上下文';

ALTER TABLE t_setting_doc ADD CONSTRAINT pk_setting_doc PRIMARY KEY (id);

-- ---------------------------- t_timeline_event ----------------------------
CREATE TABLE t_timeline_event (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    novel_id bigint NOT NULL,
    event_time varchar(64) COMMENT '故事内时间描述，如「第三年春」「主角十八岁」',
    title varchar(200) NOT NULL,
    description text,
    sort_no int DEFAULT 0 NOT NULL,
    deleted smallint DEFAULT 0 NOT NULL,
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL,
    status varchar(16) DEFAULT 'UNUSED' NOT NULL COMMENT '事件状态：UNUSED 未使用 / ONGOING 进行中 / DONE 已完成 / ABANDONED 废弃',
    CONSTRAINT ck_timeline_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_timeline_status CHECK ((status IN ('UNUSED', 'ONGOING', 'DONE', 'ABANDONED')))
) COMMENT='小说时间线事件：按故事内时间排序，供 AI 写作时对齐前后因果';

ALTER TABLE t_timeline_event ADD CONSTRAINT pk_timeline_event PRIMARY KEY (id);

-- ---------------------------- t_token_blacklist ----------------------------
CREATE TABLE t_token_blacklist (
    id bigint NOT NULL,
    jti varchar(64) NOT NULL COMMENT '令牌唯一标识',
    user_id bigint,
    token_type varchar(16) NOT NULL,
    expires_at datetime(3) NOT NULL COMMENT '令牌自身过期时间，用于定时清理',
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    CONSTRAINT ck_token_blacklist_type CHECK ((token_type IN ('access', 'refresh')))
) COMMENT='已失效令牌（登出 / 刷新轮换），无 Redis 场景下用 PG 承载';

ALTER TABLE t_token_blacklist ADD CONSTRAINT pk_token_blacklist PRIMARY KEY (id);

-- ---------------------------- t_user ----------------------------
CREATE TABLE t_user (
    id bigint NOT NULL,
    username varchar(64) NOT NULL COMMENT '登录名，未删除记录内唯一',
    password_hash varchar(100) NOT NULL COMMENT 'BCrypt 口令散列',
    nickname varchar(64),
    role varchar(32) DEFAULT 'USER' NOT NULL COMMENT 'SUPER_ADMIN 超管 / USER 普通用户',
    status varchar(16) DEFAULT 'ACTIVE' NOT NULL COMMENT 'ACTIVE 启用 / DISABLED 停用',
    must_change_password tinyint(1) DEFAULT 0 NOT NULL COMMENT '为 true 时登录后仅可访问改密接口',
    remark varchar(255),
    last_login_at datetime(3),
    last_login_ip varchar(64),
    deleted smallint DEFAULT 0 NOT NULL COMMENT '逻辑删除：0 未删除 / 1 已删除（保留 30 天可恢复）',
    created_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    created_by bigint,
    updated_at datetime(3) DEFAULT CURRENT_TIMESTAMP(3) NOT NULL,
    updated_by bigint,
    version int DEFAULT 0 NOT NULL COMMENT '乐观锁版本',
    prev_login_at datetime(3) COMMENT '上一次登录时间：登录成功时把原 last_login_at 挪过来',
    prev_login_ip varchar(64) COMMENT '上一次登录 IP',
    CONSTRAINT ck_user_deleted CHECK ((deleted IN (0, 1))),
    CONSTRAINT ck_user_role CHECK ((role IN ('SUPER_ADMIN', 'USER'))),
    CONSTRAINT ck_user_status CHECK ((status IN ('ACTIVE', 'DISABLED')))
) COMMENT='用户（平台唯一账号形态，最高数据隔离单元）';

ALTER TABLE t_user ADD CONSTRAINT pk_user PRIMARY KEY (id);

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
CREATE INDEX idx_item_novel_status ON t_item (novel_id, status);   -- 原 WHERE (deleted = 0) 在 MySQL 无对应语法，普通索引忽略
CREATE INDEX idx_master_outline_novel ON t_master_outline (novel_id, sort_no);
CREATE INDEX idx_master_outline_user ON t_master_outline (user_id);
CREATE INDEX idx_novel_agent_message_session ON t_novel_agent_message (session_id, id);
CREATE INDEX idx_novel_agent_message_user ON t_novel_agent_message (user_id);
CREATE INDEX idx_novel_agent_session_user ON t_novel_agent_session (user_id);
CREATE INDEX idx_novel_cover_novel ON t_novel_cover (novel_id, sort_no);
CREATE INDEX idx_novel_user_created ON t_novel (user_id, created_at DESC);
CREATE INDEX idx_scene_binding_user ON t_scene_binding (user_id);
CREATE INDEX idx_search_config_user ON t_search_config (user_id, created_at);   -- 原 WHERE (deleted = 0) 在 MySQL 无对应语法，普通索引忽略
CREATE INDEX idx_setting_doc_novel ON t_setting_doc (novel_id, sort);
CREATE INDEX idx_timeline_novel ON t_timeline_event (novel_id, sort_no);
CREATE INDEX idx_timeline_novel_status ON t_timeline_event (novel_id, status, sort_no);
CREATE INDEX idx_token_blacklist_expires_at ON t_token_blacklist (expires_at);
CREATE INDEX idx_user_created_at ON t_user (created_at);
CREATE INDEX idx_user_role_status ON t_user (role, status);
CREATE UNIQUE INDEX uk_ai_config_default_kind ON t_ai_config (user_id, kind, ((CASE WHEN ((is_default = 1) AND (deleted = 0)) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_ai_config_name ON t_ai_config (user_id, name, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_attribute_name ON t_character_attribute (user_id, character_id, name, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_change_history_entry ON t_change_history (user_id, chapter_id, entity_type, entity_id, field_key, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_chapter_character ON t_chapter_character (user_id, chapter_id, character_id, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_chapter_event ON t_chapter_event (user_id, chapter_id, event_id, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_character_relation_pair ON t_character_relation (user_id, novel_id, from_character_id, to_character_id, relation_type, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_event_participant ON t_event_participant (user_id, event_id, character_id, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_faction_member ON t_faction_member (user_id, faction_id, character_id, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_inspiration_session_user ON t_inspiration_session (user_id, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_inspiration_skill_code ON t_inspiration_skill (user_id, code, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_inventory_item ON t_character_inventory (user_id, character_id, item_id, ((CASE WHEN ((deleted = 0) AND (item_id IS NOT NULL)) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_inventory_name ON t_character_inventory (user_id, character_id, name, ((CASE WHEN ((deleted = 0) AND (item_id IS NULL)) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_novel_agent_session_novel ON t_novel_agent_session (novel_id, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_novel_cover_primary ON t_novel_cover (user_id, novel_id, ((CASE WHEN ((is_primary = 1) AND (deleted = 0)) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_novel_cover_sort ON t_novel_cover (user_id, novel_id, sort_no, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_novel_title ON t_novel (user_id, title, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_prompt_template_scene ON t_prompt_template (user_id, scene, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_scene_binding ON t_scene_binding (user_id, scene_code, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_search_config_default ON t_search_config (user_id, ((CASE WHEN ((deleted = 0) AND (is_default = 1) AND (enabled = 1)) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_search_config_name ON t_search_config (user_id, name, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_search_scene ON t_search_scene (user_id, scene_code, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_setting_doc_novel_category ON t_setting_doc (user_id, novel_id, category, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));
CREATE UNIQUE INDEX uk_token_blacklist_jti ON t_token_blacklist (jti);
CREATE UNIQUE INDEX uk_user_username ON t_user (username, ((CASE WHEN (deleted = 0) THEN 0 ELSE NULL END)));

SET FOREIGN_KEY_CHECKS = 1;
