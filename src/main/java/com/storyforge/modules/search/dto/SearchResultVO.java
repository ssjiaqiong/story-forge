package com.storyforge.modules.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "一条联网检索结果")
public record SearchResultVO(
        String title,
        String url,
        String snippet,
        @Schema(description = "来源域名，便于界面展示") String host) {
}
