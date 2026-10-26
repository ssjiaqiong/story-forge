package com.storyforge.modules.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 一次检索的完整结果，既返回给前端展示来源，也用于拼提示词。
 */
@Schema(description = "联网检索结果")
public record SearchBundleVO(
        @Schema(description = "是否真的执行了检索（关闭或未配置时为 false）") boolean used,
        String provider,
        String providerLabel,
        String query,
        Integer topK,
        List<SearchResultVO> results,
        @Schema(description = "检索失败原因；成功时为 null") String error,
        long elapsedMs) {

    public static SearchBundleVO skipped(String reason) {
        return new SearchBundleVO(false, null, null, null, null, List.of(), reason, 0);
    }

    public boolean hasResults() {
        return results != null && !results.isEmpty();
    }
}
