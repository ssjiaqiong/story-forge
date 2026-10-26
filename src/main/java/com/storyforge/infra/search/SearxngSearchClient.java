package com.storyforge.infra.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SearXNG 实例搜索（免密钥）。
 *
 * <p>需要实例在 {@code settings.yml} 的 {@code search.formats} 中开启 {@code json}，
 * 否则会返回 HTML，此时给出明确提示。</p>
 */
public class SearxngSearchClient implements SearchProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<SearchHit> search(SearchEndpoint endpoint, String query) {
        String base = trimSlash(endpoint.baseUrl());
        String language = endpoint.language() == null || endpoint.language().isBlank()
                ? "zh-CN" : endpoint.language();
        String url = base + "/search?q=" + SearchHttp.encode(query)
                + "&format=json&language=" + SearchHttp.encode(language)
                + "&safesearch=0&pageno=1";
        String body = SearchHttp.get(url, Map.of("Accept", "application/json"), endpoint.timeoutMs());
        String trimmed = body == null ? "" : body.trim();
        if (!trimmed.startsWith("{")) {
            throw new IllegalStateException("该 SearXNG 实例未开启 JSON 输出，请在实例设置中启用 json 格式");
        }
        try {
            JsonNode root = MAPPER.readTree(trimmed);
            JsonNode items = root.path("results");
            List<SearchHit> hits = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (hits.size() >= endpoint.topK()) {
                        break;
                    }
                    String title = item.path("title").asText("").trim();
                    String target = item.path("url").asText("").trim();
                    if (title.isEmpty() || target.isEmpty()) {
                        continue;
                    }
                    String snippet = item.path("content").asText("");
                    if (snippet.isBlank()) {
                        snippet = item.path("snippet").asText("");
                    }
                    hits.add(new SearchHit(title, target, truncate(SearchHttp.clean(snippet), 300)));
                }
            }
            return hits;
        } catch (Exception ex) {
            throw new IllegalStateException("SearXNG 返回内容无法解析：" + SearchHttp.abbreviate(trimmed));
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return SearchProvider.SEARXNG.getDefaultBaseUrl();
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
