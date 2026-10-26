package com.storyforge.infra.search;

import lombok.Getter;

import java.util.Locale;

/**
 * 联网搜索源注册表。
 *
 * <p>默认提供四个无需密钥的方案（必应 / DuckDuckGo / 维基百科 / SearXNG），
 * 另外支持两个按量付费的搜索 API（Tavily / Serper）。新增搜索源时在这里追加枚举，
 * 并在 {@link SearchClientRegistry} 中登记实现即可。</p>
 */
@Getter
public enum SearchProvider {

    BING("必应", false, "https://cn.bing.com",
            "免密钥：抓取必应网页结果，国内网络通常可直接使用。"),
    DUCKDUCKGO("DuckDuckGo", false, "https://lite.duckduckgo.com",
            "免密钥：DuckDuckGo 精简版，部分网络环境无法访问。"),
    WIKIPEDIA("维基百科", false, "https://zh.wikipedia.org",
            "免密钥：MediaWiki 搜索接口，适合查名词、历史、地理等百科资料；部分网络环境无法访问。"),
    SEARXNG("SearXNG", false, "https://searx.be",
            "免密钥：自建或公共 SearXNG 实例，需要在实例的 search.formats 中开启 json。"),
    TAVILY("Tavily", true, "https://api.tavily.com",
            "面向 AI 的搜索 API，需要 API Key，按量计费。"),
    SERPER("Serper", true, "https://google.serper.dev",
            "Google 搜索结果 API，需要 API Key，按量计费。");

    private final String label;
    private final boolean requiresKey;
    private final String defaultBaseUrl;
    private final String description;

    SearchProvider(String label, boolean requiresKey, String defaultBaseUrl, String description) {
        this.label = label;
        this.requiresKey = requiresKey;
        this.defaultBaseUrl = defaultBaseUrl;
        this.description = description;
    }

    public static SearchProvider of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String value = code.trim().toUpperCase(Locale.ROOT);
        for (SearchProvider provider : values()) {
            if (provider.name().equals(value)) {
                return provider;
            }
        }
        return null;
    }
}
