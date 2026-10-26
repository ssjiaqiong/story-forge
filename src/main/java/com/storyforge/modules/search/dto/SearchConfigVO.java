package com.storyforge.modules.search.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "联网搜索源配置")
public record SearchConfigVO(
        Long id,
        String name,
        String provider,
        String providerLabel,
        String providerDescription,
        boolean requiresKey,
        String baseUrl,
        String apiKeyMask,
        @Schema(description = "明文密钥，仅详情接口返回（编辑页回填用）") String apiKey,
        boolean apiKeyConfigured,
        Integer defaultTopK,
        String language,
        String region,
        Integer timeoutMs,
        Boolean enabled,
        Boolean isDefault,
        String remark,
        String lastTestAt,
        Boolean lastTestOk,
        Integer lastTestLatencyMs,
        String lastTestMessage,
        String createdAt) {
}
