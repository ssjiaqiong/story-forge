package com.storyforge.modules.novel.service;

import lombok.Getter;

/**
 * 封面风格预设。
 *
 * <p>每种风格映射到一段稳定的中文美术描述，注入文生图提示词。用户没选时不上风格约束，
 * 交给模型自由发挥。</p>
 */
@Getter
public enum CoverStyle {

    ANIME("动漫", "日系动漫风格，干净利落的线稿与赛璐璐上色，人物神态夸张传神，明亮通透的光影"),
    REAL("真人写实", "真人电影剧照质感，写实人物比例与皮肤纹理，电影级布光与景深，如高预算海报"),
    CG("CG 3D", "3D 渲染风格，细腻的材质与体积光，影视级 CG 概念图，带有史诗感的氛围"),
    INK("水墨国风", "中国传统水墨画风格，留白与晕染，写意笔触，淡雅古韵，意境悠远"),
    OIL("油画", "古典油画质感，厚重的笔触与丰富肌理，浓郁的色彩层次，如博物馆藏品"),
    FLAT("扁平插画", "现代扁平化商业插画，几何色块与简洁造型，高饱和配色，装饰感强");

    /** 界面显示名 */
    private final String label;
    /** 注入提示词的美术描述 */
    private final String prompt;

    CoverStyle(String label, String prompt) {
        this.label = label;
        this.prompt = prompt;
    }

    public static CoverStyle of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (CoverStyle style : values()) {
            if (style.name().equalsIgnoreCase(code.trim())) {
                return style;
            }
        }
        return null;
    }
}
