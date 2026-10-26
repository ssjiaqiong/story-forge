package com.storyforge.infra.search;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

/**
 * 搜索源实现注册表：按枚举分发，新增实现只需在这里登记。
 */
@Component
public class SearchClientRegistry {

    private final Map<SearchProvider, SearchProviderClient> clients = new EnumMap<>(SearchProvider.class);

    public SearchClientRegistry() {
        clients.put(SearchProvider.BING, new BingSearchClient());
        clients.put(SearchProvider.DUCKDUCKGO, new DuckDuckGoSearchClient());
        clients.put(SearchProvider.WIKIPEDIA, new WikipediaSearchClient());
        clients.put(SearchProvider.SEARXNG, new SearxngSearchClient());
        clients.put(SearchProvider.TAVILY, new TavilySearchClient());
        clients.put(SearchProvider.SERPER, new SerperSearchClient());
    }

    public SearchProviderClient get(SearchProvider provider) {
        SearchProviderClient client = provider == null ? null : clients.get(provider);
        if (client == null) {
            throw new IllegalStateException("不支持的搜索源");
        }
        return client;
    }
}
