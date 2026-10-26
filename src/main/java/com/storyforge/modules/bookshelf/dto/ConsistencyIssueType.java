package com.storyforge.modules.bookshelf.dto;

import lombok.Getter;

import java.util.Arrays;
import java.util.Locale;

/**
 * 一致性问题的类型。
 *
 * <p>规则层与 AI 层共用这套编码，但<b>允许的类型不同</b>：{@code STRUCTURE} 与 {@code ARCHIVE}
 * 是纯结构性问题（断号、排序异常、分析断档），规则查一次就准，让模型去猜反而会漏，
 * 因此 {@link #aiAllowed} 为 false，模型输出里出现这两个值会被丢弃。</p>
 *
 * <p>与「建书追问的 key 白名单」同理：模型只能在我们给的集合里选，自创的一律丢弃，
 * 这样落库的数据永远可枚举，前端标签不会渲染出未知类型。</p>
 */
@Getter
public enum ConsistencyIssueType {

    CHARACTER_STATE("人物状态矛盾", true, "人物在已死亡 / 失踪之后又出场，或状态与履历对不上"),
    ITEM_STATE("物品状态矛盾", true, "物品已摧毁 / 遗失 / 消耗，却仍被持有或使用"),
    TIMELINE("时间线矛盾", true, "事件顺序、时间描述或事件状态前后矛盾"),
    SETTING_CONFLICT("设定冲突", true, "正文写法与世界观、势力规矩、人物能力边界冲突"),
    ADDRESS("称谓矛盾", true, "同一人物在不同章节被用不同的称呼 / 身份，且没有交代变化"),
    FORESHADOW("伏笔未回收", true, "前文埋下的线索到后面没有交代"),
    OUTLINE_DRIFT("偏离大纲", true, "正文与本章大纲明显脱节"),
    REPEAT("重复情节", true, "相近的桥段、对话或描写在临近章节重复出现"),

    /** 规则层专用：章号重复、卷内断号、排序值异常 */
    STRUCTURE("章节结构异常", false, "章号重复、卷内断号，或排序值与卷号章号算出的结果不一致"),

    /** 规则层专用：有正文却没分析，且后面的章节已经分析过（顺序断档） */
    ARCHIVE("档案未同步", false, "本章已有正文却没有分析记录，而它后面的章节已经分析过");

    private final String label;
    private final boolean aiAllowed;
    private final String description;

    ConsistencyIssueType(String label, boolean aiAllowed, String description) {
        this.label = label;
        this.aiAllowed = aiAllowed;
        this.description = description;
    }

    /** 忽略大小写与下划线差异后匹配；匹配不到返回 {@code null} 由调用方丢弃该条。 */
    public static ConsistencyIssueType of(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (ConsistencyIssueType type : values()) {
            if (type.name().equals(normalized)) {
                return type;
            }
        }
        return null;
    }

    /** 模型输出允许的类型清单，写进提示词的白名单里。 */
    public static String aiWhitelist() {
        StringBuilder builder = new StringBuilder();
        for (ConsistencyIssueType type : Arrays.asList(values())) {
            if (!type.aiAllowed) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(type.name()).append('(').append(type.label).append(')');
        }
        return builder.toString();
    }
}
