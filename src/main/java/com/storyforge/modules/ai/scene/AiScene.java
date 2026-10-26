package com.storyforge.modules.ai.scene;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;

/**
 * AI 场景注册表。
 *
 * <p>所有调用 AI 的功能都必须在此登记，场景编码用于「设置 → 场景绑定」与「提示词模板」。
 * 新增 AI 功能时追加枚举项即可，前端下拉与绑定校验自动生效。</p>
 *
 * <p><b>只登记真实存在调用的场景。</b>本枚举同时驱动两个前端下拉，登记了却没有调用方的场景码
 * 会让用户配了永远不生效的绑定，并且会因「该接口已被场景绑定」挡住 AI 接口删除，
 * 因此不再保留纯占位项（V18 迁移已清理 12 个）。蓝图四段拆成独立场景码，
 * 既能各自编辑提示词，也支持给不同段绑定不同模型。</p>
 */
@Getter
public enum AiScene {

    OUTLINE_GEN("大纲生成", "大纲", AiConfigKind.TEXT),
    MASTER_OUTLINE("总纲生成", "大纲", AiConfigKind.TEXT),
    BODY_GEN("正文生成", "正文", AiConfigKind.TEXT),
    BLUEPRINT_WORLDVIEW("蓝图 · 世界观", "蓝图", AiConfigKind.TEXT),
    BLUEPRINT_BACKGROUND("蓝图 · 背景", "蓝图", AiConfigKind.TEXT),
    BLUEPRINT_CONFLICT("蓝图 · 简介与冲突", "蓝图", AiConfigKind.TEXT),
    BLUEPRINT_CHARACTER("蓝图 · 人物", "蓝图", AiConfigKind.TEXT),
    CHARACTER_BUILD("角色生成", "角色与设定", AiConfigKind.TEXT),
    ITEM_BUILD("物品生成", "角色与设定", AiConfigKind.TEXT),
    FACTION_BUILD("势力生成", "角色与设定", AiConfigKind.TEXT),
    EVENT_SUPPLY("事件补货", "事件", AiConfigKind.TEXT),
    ANALYSIS_GEN("章节分析", "分析与质检", AiConfigKind.TEXT),
    SYNC_EXTRACT("状态同步抽取", "分析与质检", AiConfigKind.TEXT),
    CHAPTER_REVISE("按分析修改", "分析与质检", AiConfigKind.TEXT),
    COVER_IMAGE("封面生成", "封面", AiConfigKind.IMAGE),
    COVER_TITLE("封面起名", "封面", AiConfigKind.TEXT),
    INSPIRATION_CHAT("灵感对话", "创作助手", AiConfigKind.TEXT),
    NOVEL_EXTRACT("小说要素抽取", "创作助手", AiConfigKind.TEXT),
    NOVEL_AGENT("小说智能助手", "创作助手", AiConfigKind.TEXT),
    DECONSTRUCT("拆书改编", "创作助手", AiConfigKind.TEXT);

    /** 中文名称 */
    private final String label;
    /** 界面分组 */
    private final String group;
    /** 该场景需要的接口类型：文本接口 / 图像接口 */
    private final AiConfigKind kind;

    AiScene(String label, String group, AiConfigKind kind) {
        this.label = label;
        this.group = group;
        this.kind = kind;
    }

    public static AiScene of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AiScene scene : values()) {
            if (scene.name().equalsIgnoreCase(code.trim())) {
                return scene;
            }
        }
        return null;
    }

    public static List<AiScene> ordered() {
        return Arrays.asList(values());
    }
}
