package com.storyforge.modules.bookshelf.dto;

import java.util.Locale;

/**
 * 创作约束的固定字段白名单。
 *
 * <p>建书前 AI 只能从这几个方向里挑问题问，作者作答后按 {@code 中文名：答案} 逐行拼成
 * {@code t_novel.constraint_text}，再作为模板变量 {@code {{constraints}}} 注入此后所有创作场景。</p>
 *
 * <p>白名单的意义：AI 换了说法、换了语言，落库的结构也不会走形；同时前端可以按 key 做回填，
 * 作者下次编辑时还能认出自己当初答的是哪一项。</p>
 */
public enum NovelConstraintField {

    PROTAGONIST("protagonist", "主角设定"),
    GOAL("goal", "主角目标"),
    ANTAGONIST("antagonist", "主要对手"),
    POWER("power", "力量或规则体系"),
    WORLD("world", "世界舞台"),
    OPENING("opening", "开局场景"),
    ENDING("ending", "结局倾向"),
    TONE("tone", "情绪基调与爽点"),
    TABOO("taboo", "禁区与不要出现的内容");

    private final String key;
    private final String label;

    NovelConstraintField(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** 未知 key 返回 {@code null}：调用方据此丢弃模型自创的字段，不让它进库。 */
    public static NovelConstraintField of(String key) {
        if (key == null) {
            return null;
        }
        String value = key.trim().toLowerCase(Locale.ROOT);
        for (NovelConstraintField field : values()) {
            if (field.key.equals(value)) {
                return field;
            }
        }
        return null;
    }
}
