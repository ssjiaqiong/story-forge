package com.storyforge.modules.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "搜索连通性测试结果")
public record SearchTestResultVO(
        boolean ok,
        String provider,
        String providerLabel,
        long latencyMs,
        List<SearchResultVO> results,
        String message) {
}
