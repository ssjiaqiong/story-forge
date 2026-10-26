package com.storyforge.common.api;

import lombok.Getter;

/**
 * 全局错误码。
 *
 * <p>分段约定：</p>
 * <ul>
 *   <li>{@code 0}        —— 成功</li>
 *   <li>{@code 1xxxx}    —— 通用（参数、资源）</li>
 *   <li>{@code 2xxxx}    —— 认证与权限</li>
 *   <li>{@code 3xxxx}    —— 业务规则</li>
 *   <li>{@code 4xxxx}    —— 创作域（预留）</li>
 *   <li>{@code 5xxxx}    —— AI / 流水线（预留）</li>
 *   <li>{@code 9xxxx}    —— 系统</li>
 * </ul>
 */
@Getter
public enum ErrorCode {

    SUCCESS(0, "ok", 200),

    /* ---------- 1xxxx 通用 ---------- */
    PARAM_INVALID(10001, "参数校验失败", 400),
    RESOURCE_NOT_FOUND(10002, "资源不存在", 404),
    METHOD_NOT_ALLOWED(10003, "请求方法不支持", 405),

    /* ---------- 2xxxx 认证与权限 ---------- */
    UNAUTHORIZED(20001, "未登录或登录状态已失效", 401),
    BAD_CREDENTIALS(20002, "用户名或密码错误", 401),
    USER_DISABLED(20003, "账号已停用，请联系超级管理员", 403),
    MUST_CHANGE_PASSWORD(20004, "首次登录请先修改初始密码", 403),
    OLD_PASSWORD_MISMATCH(20005, "原密码不正确", 400),
    FORBIDDEN(20006, "无权限执行该操作", 403),

    /* ---------- 3xxxx 业务规则 ---------- */
    USERNAME_EXISTS(30001, "用户名已存在", 400),
    USERNAME_RECYCLED(30002, "该用户名属于已被删除的账号，请先恢复该账号", 400),
    USER_NOT_FOUND(30003, "用户不存在", 404),
    REGISTRATION_CLOSED(30030, "系统已关闭自助注册，账号由超级管理员创建", 403),
    READONLY_SCOPE(30031, "该资源为只读，禁止修改", 403),
    SELF_OPERATION_FORBIDDEN(30032, "不能对当前登录账号执行该操作", 400),
    LAST_SUPER_ADMIN(30033, "系统至少需保留一个启用状态的超级管理员", 400),
    USER_ALREADY_DELETED(30034, "该用户已被删除", 400),

    /* ---------- 4xxxx 创作域 ---------- */
    NOVEL_TITLE_DUPLICATED(40001, "小说标题重复", 400),
    AI_CONFIG_MISSING(40002, "尚未配置可用的 AI 接口", 400),
    BODY_NOT_QUALIFIED(40003, "正文未达到质量要求", 400),
    EVENT_POOL_INSUFFICIENT(40004, "事件池数量不足", 400),
    PROMPT_VARIABLE_INVALID(40005, "提示词模板变量非法", 400),
    AI_CONFIG_NOT_FOUND(40006, "AI 接口配置不存在", 404),
    AI_CONFIG_NAME_EXISTS(40007, "接口名称已存在", 400),
    AI_SCENE_INVALID(40008, "AI 场景编码非法", 400),
    AI_CONFIG_DISABLED(40009, "该 AI 接口已停用", 400),
    AI_CONFIG_IN_USE(40010, "该接口已被场景绑定，请先解除绑定", 400),
    NOVEL_TITLE_EMPTY(40011, "小说标题不能为空", 400),
    SKILL_NOT_FOUND(40012, "技能不存在", 404),
    SKILL_BUILTIN_READONLY(40013, "内置技能不可删除，可修改或恢复默认", 400),
    SKILL_NAME_EXISTS(40014, "技能名称已存在", 400),
    INSPIRATION_EMPTY(40015, "灵感会话还没有内容，先和 AI 聊聊你的想法", 400),
    NOVEL_NOT_FOUND(40016, "小说不存在", 404),
    CHAPTER_NOT_FOUND(40017, "章节不存在", 404),
    TIMELINE_NOT_FOUND(40018, "时间线事件不存在", 404),
    ITEM_NOT_FOUND(40019, "物品不存在", 404),
    CHARACTER_NOT_FOUND(40020, "角色不存在", 404),
    TASK_NOT_FOUND(40021, "生成任务不存在或已过期", 404),
    RELATION_EXISTS(40022, "这两个角色之间已存在相同关系", 400),
    RELATION_NOT_FOUND(40023, "人物关系不存在", 404),
    CHARACTER_GENERATE_EMPTY(40024, "AI 没有生成可用的角色，请换一种描述再试", 400),
    EVENT_GENERATE_EMPTY(40025, "AI 没有生成可用的事件，请换一种描述再试", 400),
    FACTION_NOT_FOUND(40026, "势力不存在", 404),
    FACTION_MEMBER_NOT_FOUND(40027, "势力成员不存在", 404),
    FACTION_MEMBER_EXISTS(40028, "该角色已在此势力中任职", 400),
    SEARCH_CONFIG_NOT_FOUND(40029, "搜索源不存在", 404),
    SEARCH_UNAVAILABLE(40030, "还没有可用的联网搜索源，请到「设置 → 联网搜索」配置", 400),
    SEARCH_FAILED(40031, "联网搜索失败", 502),
    OUTLINE_GENERATE_EMPTY(40032, "AI 没有生成可用的大纲，请换一种描述再试", 400),
    CONTENT_GENERATE_EMPTY(40033, "AI 没有生成可用的正文，请换一种描述再试", 400),
    ITEM_GENERATE_EMPTY(40034, "AI 没有生成可用的物品，请换一种描述再试", 400),
    DECONSTRUCT_TEXT_TOO_SHORT(40035, "上传的文本太短，至少需要 3000 个汉字", 400),
    DECONSTRUCT_TEXT_TOO_LONG(40036, "上传的文本超过 500 万汉字，请拆分后分次拆书", 400),
    DECONSTRUCT_NOT_FOUND(40037, "拆书记录不存在", 404),
    DECONSTRUCT_NOT_READY(40038, "拆书尚未完成，请等待分析结束", 400),
    DECONSTRUCT_FILE_INVALID(40039, "仅支持 txt / md 等纯文本文件，请检查文件格式", 400),
    CHAPTER_ORDER_VIOLATION(40040, "分析需要按章节顺序进行，请先分析前面的章节", 400),
    CHAPTER_CONTENT_EMPTY(40041, "本章还没有正文，无法分析", 400),
    ANALYSIS_NOT_FOUND(40042, "分析记录不存在", 404),
    ATTRIBUTE_EXISTS(40043, "该属性已存在", 400),
    ATTRIBUTE_NOT_FOUND(40044, "属性不存在", 404),
    INVENTORY_EXISTS(40045, "该条目已经在背包里了", 400),
    INVENTORY_NOT_FOUND(40046, "背包条目不存在", 404),
    INVENTORY_ITEM_INVALID(40047, "关联的物品不属于本书", 400),
    PROMPT_SCENE_NOT_EDITABLE(40049, "该场景暂未开放模板编辑", 400),
    PROMPT_CONTENT_INVALID(40050, "模板内容为空或过长", 400),
    CHAPTER_NOT_ANALYZED(40051, "本章还没有分析记录，无法按分析修改", 400),
    CHAPTER_REVISE_EMPTY(40052, "AI 没有生成可用的修改稿，请补充修改要求后重试", 400),
    COVER_CONFIG_MISSING(40053, "还没有可用的图像接口，请到「设置 → AI 接口管理」新增图像接口", 400),
    COVER_GENERATE_FAILED(40054, "封面生成失败", 502),
    COVER_NOT_FOUND(40055, "封面不存在", 404),
    COVER_LIMIT_REACHED(40056, "一部小说最多保留 5 张封面，请先删除不用的再生成", 400),
    AI_CONFIG_KIND_MISMATCH(40057, "该场景需要的接口类型与所选接口不一致", 400),
    COVER_FILE_MISSING(40058, "封面图片文件已丢失，请重新生成", 404),
    FACTION_GENERATE_EMPTY(40059, "AI 没有生成可用的势力，请换一种描述再试", 400),
    AGENT_OPERATION_INVALID(40060, "智能助手返回了无法识别的修改指令", 400),
    CHAPTER_LOCKED(40061, "本章已标记为「已完成」，正文与标题不可修改；如需修改请先把状态改回草稿或写作中", 400),

    /* ---------- 5xxxx AI / 流水线 ---------- */
    AI_CALL_FAILED(50001, "AI 调用失败", 502),
    AI_PARSE_FAILED(50002, "结构化解析失败", 500),
    JOB_CANCELED(50003, "作业已取消", 400),
    CONTEXT_MISSING(50004, "上下文缺失", 400),
    AI_MODEL_LIST_FAILED(50005, "模型列表获取失败", 502),

    /* ---------- 9xxxx 系统 ---------- */
    INTERNAL_ERROR(90001, "系统内部错误", 500),
    RATE_LIMITED(90002, "请求过于频繁，请稍后再试", 429);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }
}
