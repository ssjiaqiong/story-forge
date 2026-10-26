package com.storyforge.infra.search;

/**
 * 一次检索请求的参数（已解析好的搜索源配置）。
 */
public record SearchEndpoint(
        SearchProvider provider,
        String baseUrl,
        String apiKey,
        int topK,
        String language,
        String region,
        int timeoutMs) {
}
