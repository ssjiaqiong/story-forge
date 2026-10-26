package com.storyforge.infra.search;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * DuckDuckGo 精简版（免密钥）。
 *
 * <p>{@code lite.duckduckgo.com} 输出一张结果表：每行要么是标题链接
 * （{@code class='result-link'}），要么是摘要（{@code class='result-snippet'}）。
 * 按行顺序把摘要挂到上一条结果上。</p>
 */
public class DuckDuckGoSearchClient implements SearchProviderClient {

    private static final Pattern ROW = Pattern.compile("<tr[^>]*>(.*?)</tr>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("<a([^>]*result-link[^>]*)>(.*?)</a>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF = Pattern.compile("href=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern SNIPPET = Pattern.compile("class=['\"]result-snippet['\"][^>]*>(.*?)</td>",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    @Override
    public List<SearchHit> search(SearchEndpoint endpoint, String query) {
        String base = trimSlash(endpoint.baseUrl());
        String url = base + "/lite/?q=" + SearchHttp.encode(query);
        String html = SearchHttp.get(url, Map.of(), endpoint.timeoutMs());

        List<SearchHit> hits = new ArrayList<>();
        List<String> snippets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher rows = ROW.matcher(html);
        while (rows.find()) {
            String row = rows.group(1);
            Matcher link = LINK.matcher(row);
            if (link.find()) {
                Matcher href = HREF.matcher(link.group(1));
                if (!href.find()) {
                    continue;
                }
                String target = SearchHttp.decodeUrl(href.group(1));
                if (target.startsWith("//")) {
                    target = "https:" + target;
                }
                String title = SearchHttp.clean(link.group(2));
                if (title.isEmpty() || !seen.add(target)) {
                    continue;
                }
                hits.add(new SearchHit(title, target, ""));
                continue;
            }
            Matcher snippet = SNIPPET.matcher(row);
            if (snippet.find() && hits.size() > snippets.size()) {
                snippets.add(SearchHttp.clean(snippet.group(1)));
            }
        }

        List<SearchHit> result = new ArrayList<>();
        for (int i = 0; i < hits.size() && result.size() < endpoint.topK(); i++) {
            SearchHit hit = hits.get(i);
            String snippet = i < snippets.size() ? snippets.get(i) : "";
            result.add(new SearchHit(hit.title(), hit.url(), snippet.length() > 300 ? snippet.substring(0, 300) : snippet));
        }
        return result;
    }

    private String trimSlash(String value) {
        if (value == null || value.isBlank()) {
            return SearchProvider.DUCKDUCKGO.getDefaultBaseUrl();
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
