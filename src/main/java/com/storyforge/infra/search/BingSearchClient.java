package com.storyforge.infra.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 必应网页搜索（免密钥）。
 *
 * <p>抓取 {@code /search} 的结果列表：每个结果在 {@code <li class="b_algo">} 里，
 * 标题在 {@code h2 > a}，摘要在第一个 {@code <p>}。页面结构变化时可能解析不到结果，
 * 此时返回空列表并由上层提示「换一个搜索源」。</p>
 */
public class BingSearchClient implements SearchProviderClient {

    private static final Pattern BLOCK = Pattern.compile("<li class=\"b_algo\"[^>]*>(.*?)</li>",
            Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<h2[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.DOTALL);
    private static final Pattern PARAGRAPH = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    @Override
    public List<SearchHit> search(SearchEndpoint endpoint, String query) {
        String base = trimSlash(endpoint.baseUrl());
        String url = base + "/search?q=" + SearchHttp.encode(query)
                + "&count=" + Math.min(30, Math.max(10, endpoint.topK() * 2))
                + "&setlang=zh-Hans&ensearch=0";
        String html = SearchHttp.get(url, Map.of(), endpoint.timeoutMs());

        List<SearchHit> hits = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher blocks = BLOCK.matcher(html);
        while (blocks.find() && hits.size() < endpoint.topK()) {
            String block = blocks.group(1);
            Matcher link = LINK.matcher(block);
            if (!link.find()) {
                continue;
            }
            String href = SearchHttp.decodeUrl(link.group(1));
            String title = SearchHttp.clean(link.group(2));
            if (title.isEmpty() || href.isBlank() || !seen.add(href)) {
                continue;
            }
            String snippet = "";
            Matcher paragraph = PARAGRAPH.matcher(block);
            if (paragraph.find()) {
                snippet = SearchHttp.clean(paragraph.group(1));
            }
            if (snippet.isEmpty()) {
                snippet = SearchHttp.clean(block);
            }
            hits.add(new SearchHit(title, href, truncate(snippet, 300)));
        }
        return hits;
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return SearchProvider.BING.getDefaultBaseUrl();
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
