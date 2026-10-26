package com.storyforge.modules.search.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.crypto.SecretCipher;
import com.storyforge.infra.search.SearchClientRegistry;
import com.storyforge.infra.search.SearchEndpoint;
import com.storyforge.infra.search.SearchHit;
import com.storyforge.infra.search.SearchProvider;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.search.dto.SearchBundleVO;
import com.storyforge.modules.search.dto.SearchResultVO;
import com.storyforge.modules.search.entity.SearchConfigEntity;
import com.storyforge.modules.search.entity.SearchSceneEntity;
import com.storyforge.modules.search.mapper.SearchConfigMapper;
import com.storyforge.modules.search.mapper.SearchSceneMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 联网检索实现：解析配置 → 命中缓存或调用搜索源 → 统一成结果视图。
 *
 * <p>检索失败从不抛异常：返回 {@code used=false} + {@code error}，让生成流程降级为
 * 「不参考资料继续写」，避免因为外部搜索不可用导致整个创作中断。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebSearchServiceImpl implements WebSearchService {

    private static final long CACHE_TTL_MS = 10 * 60 * 1000L;
    private static final int CACHE_MAX = 200;
    private static final int MAX_TOP_K = 10;

    private final SearchConfigMapper configMapper;
    private final SearchSceneMapper sceneMapper;
    private final SearchConfigService configService;
    private final SearchClientRegistry clientRegistry;
    private final SecretCipher secretCipher;

    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    @Override
    public SearchBundleVO search(AiScene scene, String query, Integer topK) {
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return SearchBundleVO.skipped("检索词为空");
        }
        configService.ensureDefaults();
        SearchSceneEntity binding = scene == null ? null : sceneMapper.selectOne(
                Wrappers.<SearchSceneEntity>lambdaQuery()
                        .eq(SearchSceneEntity::getSceneCode, scene.name())
                        .last("limit 1"));
        if (binding != null && Boolean.FALSE.equals(binding.getEnabled())) {
            return SearchBundleVO.skipped("该场景已关闭联网检索");
        }

        SearchConfigEntity config = binding != null && binding.getConfigId() != null
                ? configMapper.selectById(binding.getConfigId())
                : defaultConfig();
        if (config == null) {
            return SearchBundleVO.skipped("还没有可用的联网搜索源");
        }
        if (!Boolean.TRUE.equals(config.getEnabled())) {
            return SearchBundleVO.skipped("搜索源「" + config.getName() + "」已停用");
        }
        int limit = topK != null ? clamp(topK)
                : binding != null && binding.getTopK() != null ? clamp(binding.getTopK())
                : config.getDefaultTopK() == null ? 6 : clamp(config.getDefaultTopK());
        return execute(config, keyword, limit);
    }

    @Override
    public SearchBundleVO searchWith(Long configId, String query, Integer topK) {
        SearchConfigEntity config = configId == null ? defaultConfig() : configMapper.selectById(configId);
        if (config == null) {
            return SearchBundleVO.skipped("还没有可用的联网搜索源");
        }
        String keyword = query == null ? "" : query.trim();
        if (keyword.isEmpty()) {
            return SearchBundleVO.skipped("检索词为空");
        }
        int limit = topK != null ? clamp(topK)
                : config.getDefaultTopK() == null ? 6 : clamp(config.getDefaultTopK());
        return execute(config, keyword, limit);
    }

    private SearchBundleVO execute(SearchConfigEntity config, String query, int topK) {
        String key = config.getId() + "|" + topK + "|" + query.toLowerCase();
        CacheEntry cached = cache.get(key);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.createdAt() < CACHE_TTL_MS) {
            return cached.bundle();
        }

        SearchProvider provider = SearchProvider.of(config.getProvider());
        if (provider == null) {
            return SearchBundleVO.skipped("搜索源类型无效，请重新选择");
        }
        String apiKey;
        try {
            apiKey = secretCipher.decrypt(config.getApiKeyCipher());
        } catch (RuntimeException ex) {
            return new SearchBundleVO(false, provider.name(), provider.getLabel(), query, topK,
                    List.of(), "搜索源密钥无法解密，请重新填写", 0);
        }
        if (provider.isRequiresKey() && (apiKey == null || apiKey.isBlank())) {
            return new SearchBundleVO(false, provider.name(), provider.getLabel(), query, topK,
                    List.of(), provider.getLabel() + " 缺少 API Key", 0);
        }

        long startedAt = System.currentTimeMillis();
        SearchEndpoint endpoint = new SearchEndpoint(
                provider,
                normalizeBaseUrl(provider, config.getBaseUrl()),
                apiKey,
                topK,
                config.getLanguage(),
                config.getRegion(),
                config.getTimeoutMs() == null ? 15000 : config.getTimeoutMs());
        try {
            List<SearchHit> hits = clientRegistry.get(provider).search(endpoint, query);
            long elapsed = System.currentTimeMillis() - startedAt;
            SearchBundleVO bundle = new SearchBundleVO(true, provider.name(), provider.getLabel(),
                    query, topK, toResultVO(hits), hits.isEmpty() ? "没有检索到相关资料" : null, elapsed);
            cache.put(key, new CacheEntry(bundle, now));
            evictIfNeeded();
            log.info("[search] done userId={} provider={} query={} hits={} elapsed={}ms",
                    UserContextHolder.getUserIdOrNull(), provider, query, hits.size(), elapsed);
            return bundle;
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - startedAt;
            String message = readable(ex);
            log.warn("[search] failed userId={} provider={} query={}: {}",
                    UserContextHolder.getUserIdOrNull(), provider, query, message);
            return new SearchBundleVO(false, provider.name(), provider.getLabel(), query, topK,
                    List.of(), message, elapsed);
        }
    }

    private SearchConfigEntity defaultConfig() {
        return configMapper.selectList(Wrappers.<SearchConfigEntity>lambdaQuery()
                        .eq(SearchConfigEntity::getEnabled, true)
                        .orderByDesc(SearchConfigEntity::getIsDefault)
                        .orderByAsc(SearchConfigEntity::getCreatedAt)
                        .last("limit 1"))
                .stream().findFirst().orElse(null);
    }

    private List<SearchResultVO> toResultVO(List<SearchHit> hits) {
        List<SearchResultVO> results = new ArrayList<>();
        for (SearchHit hit : hits) {
            results.add(new SearchResultVO(hit.title(), hit.url(), hit.snippet(), hostOf(hit.url())));
        }
        return results;
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception ex) {
            return "";
        }
    }

    private String normalizeBaseUrl(SearchProvider provider, String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.isEmpty()) {
            return provider.getDefaultBaseUrl();
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(MAX_TOP_K, value));
    }

    private String readable(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? "联网搜索失败" : message;
    }

    private void evictIfNeeded() {
        if (cache.size() <= CACHE_MAX) {
            return;
        }
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(entry -> now - entry.getValue().createdAt() >= CACHE_TTL_MS);
        if (cache.size() <= CACHE_MAX) {
            return;
        }
        // 仍然超限：按写入时间淘汰最旧的一批
        List<Map.Entry<String, CacheEntry>> entries = new ArrayList<>(cache.entrySet());
        entries.sort((a, b) -> Long.compare(a.getValue().createdAt(), b.getValue().createdAt()));
        Iterator<Map.Entry<String, CacheEntry>> iterator = entries.iterator();
        int remove = cache.size() - CACHE_MAX;
        while (iterator.hasNext() && remove-- > 0) {
            cache.remove(iterator.next().getKey());
        }
    }

    private record CacheEntry(SearchBundleVO bundle, long createdAt) {
    }
}
