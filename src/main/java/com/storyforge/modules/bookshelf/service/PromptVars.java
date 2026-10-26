package com.storyforge.modules.bookshelf.service;

import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.SettingDocVO;
import com.storyforge.modules.novel.dto.NovelVO;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 提示词上下文变量的公共拼装。
 *
 * <p>设定与作品字段在各个创作场景里的措辞一致（{@code ● 世界观：…}），集中在这里拼，
 * 避免九个 service 各抄一遍。变量值必须与 user 段里被裁掉的那一段同源、同截断长度，
 * 否则「模板引用了变量」反而比不引用拿到更少的信息。</p>
 *
 * <p>用法：先把变量塞进 vars 交给 {@code PromptTemplateService.system}，
 * 再取 {@code usedVariables} 决定 user 段里哪些块不必再输出。</p>
 */
final class PromptVars {

    private PromptVars() {
    }

    static String text(String value, int max) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 一类设定的正文；没有内容时返回空串，让模板里的占位符直接消失。 */
    static String setting(NovelDetailVO detail, String category, int max) {
        if (detail == null || detail.getSettings() == null) {
            return "";
        }
        for (SettingDocVO doc : detail.getSettings()) {
            if (category.equals(doc.getCategory()) && doc.getContent() != null && !doc.getContent().isBlank()) {
                return text(doc.getContent(), max);
            }
        }
        return "";
    }

    /**
     * 五类设定一次性放进 vars，键名与 {@code PromptSceneRegistry} 里的变量一一对应。
     *
     * <p>截断长度按场景给：列表类场景不需要读全篇世界观，正文场景要读更多。</p>
     */
    static void putSettings(Map<String, String> vars, NovelDetailVO detail,
                            int worldview, int background, int coreConflict, int highlights, int style) {
        vars.put("worldview", setting(detail, "WORLDVIEW", worldview));
        vars.put("background", setting(detail, "BACKGROUND", background));
        vars.put("coreConflict", setting(detail, "CORE_CONFLICT", coreConflict));
        vars.put("highlights", setting(detail, "HIGHLIGHT", highlights));
        vars.put("style", setting(detail, "STYLE", style));
    }

    /** 作品级字段：标签与简介。书名、题材由各场景自行拼（大小写与缺省文案不同）。 */
    static void putNovel(Map<String, String> vars, NovelVO novel, int synopsisMax) {
        if (novel == null) {
            vars.put("tags", "");
            vars.put("synopsis", "");
            return;
        }
        vars.put("tags", novel.getTags() == null ? "" : String.join("、", novel.getTags()));
        vars.put("synopsis", text(novel.getIntro(), synopsisMax));
    }

    /** 空值统一成空串，模板里的占位符才不会渲染成 "null"。 */
    static Map<String, String> clean(Map<String, String> vars) {
        Map<String, String> result = new LinkedHashMap<>(vars);
        result.replaceAll((key, value) -> value == null ? "" : value);
        return result;
    }

    /**
     * 创作约束：建书前追问出来的那几十个字。
     *
     * <p>它本来就精炼（每条一句话），所以截断长度给得比设定宽松；没填过就是空串，
     * 模板里的 {@code {{constraints}}} 直接消失，不留下空段落。</p>
     */
    static String constraint(NovelVO novel, int max) {
        return novel == null ? "" : text(novel.getConstraintText(), max);
    }

    static String constraint(NovelDetailVO detail, int max) {
        return detail == null ? "" : constraint(detail.getNovel(), max);
    }

    /**
     * 往 user 消息里追加【创作约束】块。
     *
     * <p>模板已经引用 {@code {{constraints}}} 时不再重复输出——约束放进 system 指令段，
     * 比跟在资料里更容易被模型当成硬要求。</p>
     */
    static void appendConstraint(StringBuilder builder, NovelVO novel,
                                 java.util.Set<String> used, int max) {
        if (!emit(used, "constraints")) {
            return;
        }
        String value = constraint(novel, max);
        if (value.isEmpty()) {
            return;
        }
        builder.append("\n【创作约束（作者已确认，必须遵守）】\n").append(value).append("\n");
    }

    static void appendConstraint(StringBuilder builder, NovelDetailVO detail,
                                 java.util.Set<String> used, int max) {
        if (detail == null) {
            return;
        }
        appendConstraint(builder, detail.getNovel(), used, max);
    }

    /**
     * 模板已经引用了这个上下文变量时返回 {@code false}：user 段里不再重复输出同一块。
     *
     * <p>默认模板不引用任何上下文变量，集合为空，因此现有行为与改造前完全一致。</p>
     */
    static boolean emit(java.util.Set<String> used, String variable) {
        return used == null || !used.contains(variable);
    }

    /** 设定类别 -> 模板变量名；未知类别返回空串（永远 emit，不会被误裁）。 */
    static String variableOf(String category) {
        if (category == null) {
            return "";
        }
        return switch (category) {
            case "WORLDVIEW" -> "worldview";
            case "BACKGROUND" -> "background";
            case "CORE_CONFLICT" -> "coreConflict";
            case "HIGHLIGHT" -> "highlights";
            case "STYLE" -> "style";
            default -> "";
        };
    }
}
