package com.storyforge.infra.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 维基百科搜索（MediaWiki API，免密钥）。
 *
 * <p>适合查名词、历史、地理、人物等百科资料；不适合新闻与实时信息。</p>
 */
public class WikipediaSearchClient implements SearchProviderClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public List<SearchHit> search(SearchEndpoint endpoint, String query) {
        String base = trimSlash(endpoint.baseUrl());
        String url = base + "/w/api.php?action=query&list=search&format=json&utf8=1"
                + "&srlimit=" + endpoint.topK()
                + "&srsearch=" + SearchHttp.encode(query)
                + "&srprop=snippet";
        String body = SearchHttp.get(url, Map.of(), endpoint.timeoutMs());
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode items = root.path("query").path("search");
            List<SearchHit> hits = new ArrayList<>();
            if (items.isArray()) {
                for (JsonNode item : items) {
                    String title = item.path("title").asText("").trim();
                    if (title.isEmpty()) {
                        continue;
                    }
                    String snippet = SearchHttp.clean(item.path("snippet").asText(""));
                    String pageUrl = base + "/wiki/" + SearchHttp.encode(title.replace(' ', '_')).replace("+", "_");
                    hits.add(new SearchHit(title, pageUrl, truncate(snippet, 300)));
                }
            }
            return hits;
        } catch (Exception ex) {
            throw new IllegalStateException("维基百科返回内容无法解析：" + SearchHttp.abbreviate(body));
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return SearchProvider.WIKIPEDIA.getDefaultBaseUrl();
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
