package com.storyforge.modules.bookshelf.service;

import com.storyforge.infra.ai.PromptKit;
import com.storyforge.modules.bookshelf.dto.ChapterReviseRequest;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「按分析修改」的提示词。
 *
 * <p>与其它场景不同，这里的输入不是「让 AI 自由创作」，而是「带着审稿单改稿」：
 * 系统段管改稿分寸（哪能改、哪不能改），用户段把勾选的问题逐条摊开，
 * 并附上本章大纲，让「大纲没做到」这类问题有据可依。</p>
 */
@Component
public class ChapterRevisePromptBuilder {

    /** 一次最多处理这么多条意见：再多模型会挑着改，反而不如分两轮 */
    private static final int MAX_ITEMS = 24;
    private static final int MAX_ITEM_TEXT = 300;
    private static final int MAX_INSTRUCTION = 2000;
    private static final int MAX_SUMMARY_CHARS = 1200;

    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    public ChapterRevisePromptBuilder(
            com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /** 优先用用户模板（含锁定的输出契约），未接入时回退内置 */
    public String system(String novelTitle, Integer chapterNo, int targetWords) {
        String rendered = promptTemplateService.system(com.storyforge.modules.ai.scene.AiScene.CHAPTER_REVISE,
                Map.of(
                        "novelTitle", hasText(novelTitle) ? novelTitle : "这本书",
                        "chapterNo", chapterNo == null ? "-" : String.valueOf(chapterNo),
                        "targetWords", String.valueOf(targetWords)));
        return rendered.isBlank() ? fallbackSystem() : rendered;
    }

    public String fallbackSystem() {
        return """
                你是网络小说的改稿编辑，负责按审稿意见修改作者已经写完的这一章。
                你改的是别人的稿子，不是重写一遍。

                【改稿纪律】
                1. 只改被点出的地方，其余原样保留：人物姓名、身份、关系、世界规则、时间线、章节标题都不许动。
                2. 保留作者的语言习惯与叙事视角：不换人称、不换语气，不把市井口语改成书面语。
                3. 该补的补到位：补情节、补细节、补因果；补了就要挤掉空转的段落，不要只往里塞字。
                4. 不新增主要人物与新设定；非补不可时，只能是不影响后续走向的小角色与背景细节。
                5. 篇幅控制在目标字数上下，不为凑字注水，也不为省事砍掉有效情节。
                """ + PromptKit.PROSE_RULES;
    }

    public String prompt(ChapterEntity chapter,
                         String novelTitle,
                         String genre,
                         ChapterAnalysisVO analysis,
                         List<ChapterReviseRequest.ReviseItem> items,
                         String instruction,
                         String intensity,
                         int targetWords,
                         int wordCountBefore) {
        StringBuilder builder = new StringBuilder();
        builder.append("【作品】").append(nullToEmpty(novelTitle));
        if (hasText(genre)) {
            builder.append("（").append(genre).append("）");
        }
        builder.append('\n');
        builder.append("【本章】第 ").append(chapter.getChapterNo()).append(" 章 ")
                .append(nullToEmpty(chapter.getTitle())).append('\n');
        builder.append("【本章大纲】")
                .append(hasText(chapter.getSummary()) ? clip(chapter.getSummary(), MAX_SUMMARY_CHARS)
                        : "（作者没有写本章大纲）")
                .append('\n');

        if (analysis != null) {
            builder.append("【上次分析】大纲贴合 ").append(analysis.outlineScore()).append(" 分（")
                    .append(nullToEmpty(analysis.outlineVerdict())).append("）；AI 率 ")
                    .append(analysis.aiScore()).append(" 分（")
                    .append(nullToEmpty(analysis.aiVerdict())).append("）\n");
        }

        builder.append("【字数】修改前 ").append(wordCountBefore)
                .append(" 个汉字，修改后请落在 ").append(targetWords).append(" 字左右\n");
        builder.append("【修改幅度】").append(intensityText(intensity)).append('\n');

        builder.append("\n【必须修正的问题】\n");
        List<ChapterReviseRequest.ReviseItem> valid = normalize(items);
        if (valid.isEmpty()) {
            builder.append("（作者没有勾选具体问题，按下面的附加要求改）\n");
        } else {
            int index = 0;
            for (Map.Entry<String, List<ChapterReviseRequest.ReviseItem>> group : group(valid).entrySet()) {
                builder.append("◆ ").append(group.getKey()).append('\n');
                for (ChapterReviseRequest.ReviseItem item : group.getValue()) {
                    builder.append(++index).append(". ");
                    if (hasText(item.detail()) && !item.detail().equals(item.text())) {
                        builder.append(item.text()).append("（").append(item.detail()).append("）");
                    } else {
                        builder.append(item.text());
                    }
                    builder.append('\n');
                }
            }
        }

        if (hasText(instruction)) {
            builder.append("\n【作者附加要求】\n").append(clip(instruction.trim(), MAX_INSTRUCTION)).append('\n');
        }

        builder.append("\n【原文】\n").append(nullToEmpty(chapter.getContent())).append('\n');

        builder.append("""

                【任务】
                按上面的问题逐条落实，改写本章正文。要求：
                - 每一条问题都要能在改后的正文里找到对应的处理，不要只改一两条。
                - 输出完整正文，从头到尾，不能只给改动片段，不能出现「此处略」「同上」这类省略。
                - 不要写章节标题、不要写修改说明到 content 里；改动说明放在 notes。
                - 保留原文的段落切分；只有整段重写时才重新分段。
                """);
        return builder.toString();
    }

    /** 过滤空项、截断超长文本、去重并限流 */
    private List<ChapterReviseRequest.ReviseItem> normalize(List<ChapterReviseRequest.ReviseItem> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        Map<String, ChapterReviseRequest.ReviseItem> unique = new LinkedHashMap<>();
        for (ChapterReviseRequest.ReviseItem item : items) {
            if (item == null || !hasText(item.text())) {
                continue;
            }
            String text = clip(item.text().trim(), MAX_ITEM_TEXT);
            String detail = hasText(item.detail()) ? clip(item.detail().trim(), MAX_ITEM_TEXT) : "";
            unique.putIfAbsent((typeLabel(item.type()) + "|" + text), new ChapterReviseRequest.ReviseItem(
                    typeLabel(item.type()), text, detail));
            if (unique.size() >= MAX_ITEMS) {
                break;
            }
        }
        return List.copyOf(unique.values());
    }

    /** 按类型分组，模型读起来比一长串编号清晰 */
    private Map<String, List<ChapterReviseRequest.ReviseItem>> group(List<ChapterReviseRequest.ReviseItem> items) {
        Map<String, List<ChapterReviseRequest.ReviseItem>> grouped = new LinkedHashMap<>();
        for (ChapterReviseRequest.ReviseItem item : items) {
            grouped.computeIfAbsent(item.type(), key -> new java.util.ArrayList<>()).add(item);
        }
        return grouped;
    }

    private String typeLabel(String type) {
        if (type == null) {
            return "修改意见";
        }
        return switch (type.trim().toUpperCase()) {
            case "OUTLINE_MISS" -> "大纲没做到 / 写歪了";
            case "OUTLINE_SUGGESTION" -> "审稿建议";
            case "AI_SIGNAL" -> "AI 味问题";
            case "AI_EXAMPLE" -> "AI 味原句";
            case "LENGTH" -> "字数问题";
            default -> "修改意见";
        };
    }

    private String intensityText(String intensity) {
        if (intensity == null) {
            return "中度：只改相关段落，允许补写必要的过渡，场景顺序不动";
        }
        return switch (intensity.trim().toUpperCase()) {
            case "LIGHT" -> "轻度：只改被点出的句子与段落，其余一字不动，不增删场景";
            case "HEAVY" -> "重度：允许重写段落、调整场景顺序与节奏，但故事结论与关键事实不变";
            default -> "中度：只改相关段落，允许补写必要的过渡，场景顺序不动";
        };
    }

    private String clip(String text, int max) {
        if (!hasText(text)) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "……";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
