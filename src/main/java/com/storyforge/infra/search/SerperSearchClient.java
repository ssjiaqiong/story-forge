package com.storyforge.infra.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serper（Google 搜索结果 API，需要 API Key）。
 */
public class SerperSearchClient implements SearchProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<SearchHit> search(SearchEndpoint endpoint, String query) {
        if (endpoint.apiKey() == null || endpoint.apiKey().isBlank()) {
            throw new IllegalStateException("Serper 需要 API Key");
        }
        String base = endpoint.baseUrl() == null || endpoint.baseUrl().isBlank()
                ? SearchProvider.SERPER.getDefaultBaseUrl() : trimSlash(endpoint.baseUrl());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("q", query);
        payload.put("num", Math.min(10, endpoint.topK()));
        payload.put("hl", "zh-cn");
        payload.put("gl", endpoint.region() == null || endpoint.region().isBlank() ? "cn" : endpoint.region());

        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Serper 请求构造失败");
        }
        String response = SearchHttp.postJson(base + "/search", body,
                Map.of("X-API-KEY", endpoint.apiKey()), endpoint.timeoutMs());
        try {
            JsonNode root = MAPPER.readTree(response);
            JsonNode items = root.path("organic");
            List<SearchHit> hits = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (hits.size() >= endpoint.topK()) {
                        break;
                    }
                    String title = item.path("title").asText("").trim();
                    String target = item.path("link").asText("").trim();
                    if (title.isEmpty() || target.isEmpty()) {
                        continue;
                    }
                    String snippet = SearchHttp.clean(item.path("snippet").asText(""));
                    hits.add(new SearchHit(title, target, truncate(snippet, 300)));
                }
            }
            return hits;
        } catch (Exception ex) {
            throw new IllegalStateException("Serper 返回内容无法解析：" + SearchHttp.abbreviate(response));
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
