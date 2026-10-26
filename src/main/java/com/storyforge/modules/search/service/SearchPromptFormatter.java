package com.storyforge.modules.search.service;

import com.storyforge.modules.search.dto.SearchBundleVO;
import com.storyforge.modules.search.dto.SearchResultVO;

/**
 * 把检索结果拼成提示词片段。
 *
 * <p>明确要求模型只把资料当事实与细节来源，不得照抄、不得提及检索过程，
 * 避免生成内容出现「根据搜索结果」这类出戏表达。</p>
 */
public final class SearchPromptFormatter {

    private SearchPromptFormatter() {
    }

    public static String format(SearchBundleVO bundle, int maxItems) {
        if (bundle == null || !bundle.hasResults()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("\n【联网资料】\n")
                .append("下面是从网上检索到的背景资料，只用来补充真实细节（地名、行业流程、年代物件、术语等）。\n")
                .append("要求：不要照抄原文，不要在正文里提到「搜索」「资料」「网上」等字样，与设定冲突时以设定为准。\n");
        int index = 1;
        for (SearchResultVO item : bundle.results()) {
            if (index > maxItems) {
                break;
            }
            builder.append(index++).append(". ").append(item.title());
            if (item.host() != null && !item.host().isBlank()) {
                builder.append("（来源：").append(item.host()).append("）");
            }
            builder.append("\n");
            if (item.snippet() != null && !item.snippet().isBlank()) {
                builder.append("   ").append(truncate(item.snippet(), 240)).append("\n");
            }
        }
        builder.append("\n");
        return builder.toString();
    }

    private static String truncate(String value, int max) {
        String text = value.replaceAll("\\s+", " ").trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
