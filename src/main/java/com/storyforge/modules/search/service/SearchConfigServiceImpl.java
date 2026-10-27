package com.storyforge.modules.search.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.crypto.SecretCipher;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.search.SearchClientRegistry;
import com.storyforge.infra.search.SearchEndpoint;
import com.storyforge.infra.search.SearchHit;
import com.storyforge.infra.search.SearchProvider;
import com.storyforge.modules.search.dto.SearchConfigRequest;
import com.storyforge.modules.search.dto.SearchConfigVO;
import com.storyforge.modules.search.dto.SearchResultVO;
import com.storyforge.modules.search.dto.SearchTestRequest;
import com.storyforge.modules.search.dto.SearchTestResultVO;
import com.storyforge.modules.search.entity.SearchConfigEntity;
import com.storyforge.modules.search.entity.SearchSceneEntity;
import com.storyforge.modules.search.mapper.SearchConfigMapper;
import com.storyforge.modules.search.mapper.SearchSceneMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 搜索源配置实现。
 *
 * <p>默认搜索源采用「免密钥优先」：首次进入时自动写入必应（默认）、DuckDuckGo、维基百科，
 * 用户可在设置页增删或改用 SearXNG / Tavily / Serper。</p>
 *
 * <p>初始化是惰性 + 幂等的：设置页首屏会同时请求搜索源列表与场景绑定，两个请求都会尝试初始化，
 * 因此初始化过程按用户加锁，并以「(user_id, name) 唯一索引」兜底，任何一方重复写入都不会报错。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchConfigServiceImpl implements SearchConfigService {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String DEFAULT_TEST_QUERY = "人工智能";
    private static final int DEFAULT_TOP_K = 6;

    /** 首次初始化写入的免密钥搜索源：必应作为默认源，其余按顺序作为备选。 */
    private static final List<DefaultSource> DEFAULT_SOURCES = List.of(
            new DefaultSource("必应", SearchProvider.BING, "免密钥，国内网络通常可用"),
            new DefaultSource("DuckDuckGo", SearchProvider.DUCKDUCKGO, "免密钥，部分网络环境无法访问"),
            new DefaultSource("维基百科", SearchProvider.WIKIPEDIA, "免密钥，适合查百科类资料"));

    /** 初始化默认源时的按用户锁：并发请求只有一个线程真正写入。 */
    private final ConcurrentHashMap<Long, Object> seedLocks = new ConcurrentHashMap<>();

    private final SearchConfigMapper configMapper;
    private final SearchSceneMapper sceneMapper;
    private final SecretCipher secretCipher;
    private final SearchClientRegistry clientRegistry;

    @Override
    public List<SearchConfigVO> list() {
        ensureDefaults();
        return configMapper.selectList(Wrappers.<SearchConfigEntity>lambdaQuery()
                        .orderByDesc(SearchConfigEntity::getIsDefault)
                        .orderByAsc(SearchConfigEntity::getCreatedAt))
                .stream()
                .map(entity -> toVO(entity, false))
                .toList();
    }

    @Override
    public SearchConfigVO detail(Long id) {
        return toVO(require(id), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SearchConfigVO create(SearchConfigRequest request) {
        Long userId = UserContextHolder.requireUserId();
        SearchProvider provider = requireProvider(request.getProvider());
        String name = requireName(request.getName());
        ensureNameAvailable(name, null);

        String apiKey = trimToNull(request.getApiKey());
        if (provider.isRequiresKey() && apiKey == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, provider.getLabel() + " 需要填写 API Key");
        }

        boolean enabled = request.getEnabled() == null || request.getEnabled();
        boolean isDefault = Boolean.TRUE.equals(request.getIsDefault());
        long existing = configMapper.selectCount(Wrappers.<SearchConfigEntity>lambdaQuery());
        if (existing == 0) {
            isDefault = true;
        }
        if (isDefault) {
            enabled = true;
            clearDefault();
        }

        SearchConfigEntity entity = new SearchConfigEntity();
        entity.setUserId(userId);
        entity.setName(name);
        entity.setProvider(provider.name());
        entity.setBaseUrl(normalizeBaseUrl(provider, request.getBaseUrl()));
        if (apiKey != null) {
            entity.setApiKeyCipher(secretCipher.encrypt(apiKey));
            entity.setApiKeyMask(secretCipher.mask(apiKey));
        }
        entity.setDefaultTopK(request.getDefaultTopK() == null ? DEFAULT_TOP_K : request.getDefaultTopK());
        entity.setLanguage(hasText(request.getLanguage()) ? request.getLanguage().trim() : "zh-CN");
        entity.setRegion(trimToNull(request.getRegion()));
        entity.setTimeoutMs(request.getTimeoutMs() == null ? 15000 : request.getTimeoutMs());
        entity.setEnabled(enabled);
        entity.setIsDefault(isDefault);
        entity.setRemark(trimToNull(request.getRemark()));
        entity.setDeleted(0);
        entity.setVersion(0);
        try {
            configMapper.insert(entity);
        } catch (DataIntegrityViolationException ex) {
            // 预检与写入之间被并发请求插入（例如首屏初始化默认源的同一时刻保存表单）
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "同名搜索源已存在，可直接使用列表中的「" + name + "」");
        }
        log.info("[search] config created userId={} name={} provider={} default={}",
                userId, name, provider, isDefault);
        return toVO(entity, false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SearchConfigVO update(Long id, SearchConfigRequest request) {
        SearchConfigEntity entity = require(id);
        SearchProvider provider = hasText(request.getProvider())
                ? requireProvider(request.getProvider())
                : SearchProvider.of(entity.getProvider());
        boolean providerChanged = provider != null && !provider.name().equals(entity.getProvider());

        if (hasText(request.getName())) {
            String name = requireName(request.getName());
            ensureNameAvailable(name, id);
            entity.setName(name);
        }
        if (provider != null) {
            entity.setProvider(provider.name());
        }
        if (hasText(request.getBaseUrl())) {
            entity.setBaseUrl(normalizeBaseUrl(provider, request.getBaseUrl()));
        } else if (providerChanged) {
            entity.setBaseUrl(provider.getDefaultBaseUrl());
        }
        if (hasText(request.getApiKey())) {
            String apiKey = request.getApiKey().trim();
            entity.setApiKeyCipher(secretCipher.encrypt(apiKey));
            entity.setApiKeyMask(secretCipher.mask(apiKey));
        }
        if (provider != null && provider.isRequiresKey() && !hasText(entity.getApiKeyCipher())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, provider.getLabel() + " 需要填写 API Key");
        }
        if (request.getDefaultTopK() != null) {
            entity.setDefaultTopK(request.getDefaultTopK());
        }
        if (hasText(request.getLanguage())) {
            entity.setLanguage(request.getLanguage().trim());
        }
        if (request.getRegion() != null) {
            entity.setRegion(trimToNull(request.getRegion()));
        }
        if (request.getTimeoutMs() != null) {
            entity.setTimeoutMs(request.getTimeoutMs());
        }
        if (request.getRemark() != null) {
            entity.setRemark(trimToNull(request.getRemark()));
        }

        if (request.getEnabled() != null) {
            entity.setEnabled(request.getEnabled());
            if (!request.getEnabled()) {
                // 停用的搜索源不能保持默认身份
                entity.setIsDefault(false);
            }
        }
        if (Boolean.TRUE.equals(request.getIsDefault())) {
            clearDefault();
            entity.setEnabled(true);
            entity.setIsDefault(true);
        }
        configMapper.updateById(entity);
        ensureDefaultExists();
        return toVO(require(id), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SearchConfigEntity entity = require(id);
        // 场景绑定改为继承默认源，避免删除时报错
        sceneMapper.update(null, Wrappers.<SearchSceneEntity>lambdaUpdate()
                .eq(SearchSceneEntity::getConfigId, id)
                .set(SearchSceneEntity::getConfigId, null));
        configMapper.deleteById(entity.getId());
        ensureDefaultExists();
        log.info("[search] config deleted userId={} name={}", entity.getUserId(), entity.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SearchConfigVO setDefault(Long id) {
        SearchConfigEntity entity = require(id);
        if (!Boolean.TRUE.equals(entity.getEnabled())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先启用该搜索源，再设为默认");
        }
        clearDefault();
        entity.setIsDefault(true);
        configMapper.updateById(entity);
        return toVO(require(id), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SearchConfigVO toggle(Long id, boolean enabled) {
        SearchConfigEntity entity = require(id);
        entity.setEnabled(enabled);
        if (!enabled) {
            entity.setIsDefault(false);
        }
        configMapper.updateById(entity);
        ensureDefaultExists();
        return toVO(require(id), false);
    }

    @Override
    public SearchTestResultVO test(Long id, SearchTestRequest request) {
        SearchConfigEntity entity = require(id);
        String query = request != null && hasText(request.getQuery())
                ? request.getQuery().trim() : DEFAULT_TEST_QUERY;
        SearchProvider provider = SearchProvider.of(entity.getProvider());
        SearchEndpoint endpoint = new SearchEndpoint(
                provider,
                normalizeBaseUrl(provider, entity.getBaseUrl()),
                secretCipher.decrypt(entity.getApiKeyCipher()),
                entity.getDefaultTopK() == null ? DEFAULT_TOP_K : entity.getDefaultTopK(),
                entity.getLanguage(),
                entity.getRegion(),
                entity.getTimeoutMs() == null ? 15000 : entity.getTimeoutMs());

        long startedAt = System.currentTimeMillis();
        try {
            List<SearchHit> hits = clientRegistry.get(provider).search(endpoint, query);
            long latency = System.currentTimeMillis() - startedAt;
            String message = hits.isEmpty() ? "请求成功，但没有检索到结果" : "共检索到 " + hits.size() + " 条结果";
            saveTestResult(entity.getId(), true, latency, message);
            return new SearchTestResultVO(true, provider.name(), provider.getLabel(), latency,
                    toResultVO(hits), message);
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startedAt;
            String message = readable(ex);
            saveTestResult(entity.getId(), false, latency, message);
            log.warn("[search] test failed provider={} query={}: {}", provider, query, message);
            return new SearchTestResultVO(false, provider.name(), provider.getLabel(), latency,
                    List.of(), message);
        }
    }

    @Override
    public SearchTestResultVO probe(SearchTestRequest request) {
        SearchConfigEntity saved = request.getId() == null ? null : configMapper.selectById(request.getId());
        SearchProvider provider = SearchProvider.of(hasText(request.getProvider())
                ? request.getProvider()
                : saved == null ? null : saved.getProvider());
        if (provider == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请选择搜索源");
        }
        String baseUrl = hasText(request.getBaseUrl())
                ? normalizeBaseUrl(provider, request.getBaseUrl())
                : saved == null ? provider.getDefaultBaseUrl() : normalizeBaseUrl(provider, saved.getBaseUrl());
        String apiKey = hasText(request.getApiKey())
                ? request.getApiKey().trim()
                : saved == null ? null : secretCipher.decrypt(saved.getApiKeyCipher());
        if (provider.isRequiresKey() && !hasText(apiKey)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, provider.getLabel() + " 需要填写 API Key");
        }
        String query = hasText(request.getQuery()) ? request.getQuery().trim() : DEFAULT_TEST_QUERY;
        SearchEndpoint endpoint = new SearchEndpoint(provider, baseUrl, apiKey,
                request.getTopK() == null ? 5 : request.getTopK(), "zh-CN", null, 15000);
        long startedAt = System.currentTimeMillis();
        try {
            List<SearchHit> hits = clientRegistry.get(provider).search(endpoint, query);
            long latency = System.currentTimeMillis() - startedAt;
            String message = hits.isEmpty() ? "请求成功，但没有检索到结果" : "共检索到 " + hits.size() + " 条结果";
            return new SearchTestResultVO(true, provider.name(), provider.getLabel(), latency,
                    toResultVO(hits), message);
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startedAt;
            String message = readable(ex);
            log.warn("[search] probe failed provider={} query={}: {}", provider, query, message);
            return new SearchTestResultVO(false, provider.name(), provider.getLabel(), latency,
                    List.of(), message);
        }
    }

    /**
     * 幂等初始化默认搜索源。
     *
     * <p>注意：这里刻意不加 {@code @Transactional}。并发写入撞到唯一索引时，PostgreSQL 会让当前事务进入
     * aborted 状态，同事务内的后续语句全部失败；交给每条语句自动提交，失败的那一条独立回滚即可。</p>
     */
    @Override
    public void ensureDefaults() {
        Long userId = UserContextHolder.requireUserId();
        if (configMapper.countAllByUser(userId) > 0) {
            ensureDefaultExists();
            return;
        }
        Object lock = seedLocks.computeIfAbsent(userId, key -> new Object());
        try {
            synchronized (lock) {
                if (configMapper.countAllByUser(userId) > 0) {
                    ensureDefaultExists();
                    return;
                }
                int inserted = seedDefaults(userId);
                if (inserted > 0) {
                    log.info("[search] seeded default configs userId={} count={}", userId, inserted);
                }
            }
        } finally {
            seedLocks.remove(userId, lock);
        }
        ensureDefaultExists();
    }

    /* ------------------------------------------------------------------ */

    private int seedDefaults(Long userId) {
        int inserted = 0;
        for (DefaultSource source : DEFAULT_SOURCES) {
            if (configMapper.countByUserName(userId, source.name()) > 0) {
                // 已经是初始化过的账号（或另一实例已写入），跳过
                continue;
            }
            try {
                insertDefault(userId, source);
                inserted++;
            } catch (DataIntegrityViolationException ex) {
                // 另一个请求（或另一个实例）刚刚写入同名默认源，唯一索引生效，忽略即可
                log.debug("[search] default source already exists userId={} name={}", userId, source.name());
            }
        }
        return inserted;
    }

    private void insertDefault(Long userId, DefaultSource source) {
        SearchConfigEntity entity = new SearchConfigEntity();
        entity.setUserId(userId);
        entity.setName(source.name());
        entity.setProvider(source.provider().name());
        entity.setBaseUrl(source.provider().getDefaultBaseUrl());
        entity.setDefaultTopK(DEFAULT_TOP_K);
        entity.setLanguage("zh-CN");
        entity.setTimeoutMs(15000);
        entity.setEnabled(true);
        // 默认身份统一在 ensureDefaultExists() 里授予，避免并发时撞 (user_id) WHERE is_default 唯一索引
        entity.setIsDefault(false);
        entity.setRemark(source.remark());
        entity.setDeleted(0);
        entity.setVersion(0);
        configMapper.insert(entity);
    }

    private void clearDefault() {
        configMapper.update(null, Wrappers.<SearchConfigEntity>lambdaUpdate()
                .eq(SearchConfigEntity::getIsDefault, true)
                .set(SearchConfigEntity::getIsDefault, false));
    }

    /** 保证至少有一个启用中的默认源（优先必应），没有任何可用源时保持无默认。 */
    private void ensureDefaultExists() {
        Long defaults = configMapper.selectCount(Wrappers.<SearchConfigEntity>lambdaQuery()
                .eq(SearchConfigEntity::getIsDefault, true)
                .eq(SearchConfigEntity::getEnabled, true));
        if (defaults != null && defaults > 0) {
            return;
        }
        List<SearchConfigEntity> candidates = configMapper.selectList(
                Wrappers.<SearchConfigEntity>lambdaQuery()
                        .eq(SearchConfigEntity::getEnabled, true)
                        .orderByAsc(SearchConfigEntity::getCreatedAt));
        SearchConfigEntity candidate = candidates.stream()
                .filter(item -> SearchProvider.BING.name().equals(item.getProvider()))
                .findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.get(0));
        if (candidate == null || Boolean.TRUE.equals(candidate.getIsDefault())) {
            return;
        }
        try {
            configMapper.update(null, Wrappers.<SearchConfigEntity>lambdaUpdate()
                    .eq(SearchConfigEntity::getId, candidate.getId())
                    .set(SearchConfigEntity::getIsDefault, true));
        } catch (DataIntegrityViolationException ex) {
            // 并发下别的事务已设好默认源，忽略
            log.debug("[search] default source already promoted userId={}", candidate.getUserId());
        }
    }

    private SearchConfigEntity require(Long id) {
        SearchConfigEntity entity = id == null ? null : configMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.SEARCH_CONFIG_NOT_FOUND);
        }
        return entity;
    }

    private SearchProvider requireProvider(String code) {
        SearchProvider provider = SearchProvider.of(code);
        if (provider == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "搜索源类型非法");
        }
        return provider;
    }

    private String requireName(String name) {
        if (!hasText(name)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请填写搜索源名称");
        }
        String value = name.trim();
        if (value.length() > 100) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "搜索源名称不能超过 100 字");
        }
        return value;
    }

    private void ensureNameAvailable(String name, Long excludeId) {
        Long exists = configMapper.selectCount(Wrappers.<SearchConfigEntity>lambdaQuery()
                .eq(SearchConfigEntity::getName, name)
                .ne(excludeId != null, SearchConfigEntity::getId, excludeId));
        if (exists != null && exists > 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "同名搜索源已存在，可直接使用列表中的「" + name + "」");
        }
    }

    private String normalizeBaseUrl(SearchProvider provider, String baseUrl) {
        String value = baseUrl == null ? "" : baseUrl.trim();
        if (value.isEmpty()) {
            return provider.getDefaultBaseUrl();
        }
        String lower = value.toLowerCase();
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "接口地址必须以 http:// 或 https:// 开头");
        }
        if (value.length() > 500) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "接口地址过长");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void saveTestResult(Long id, boolean ok, long latency, String message) {
        configMapper.update(null, Wrappers.<SearchConfigEntity>lambdaUpdate()
                .eq(SearchConfigEntity::getId, id)
                .set(SearchConfigEntity::getLastTestAt, LocalDateTime.now())
                .set(SearchConfigEntity::getLastTestOk, ok)
                .set(SearchConfigEntity::getLastTestLatencyMs, (int) latency)
                .set(SearchConfigEntity::getLastTestMessage,
                        message == null ? null : message.length() > 500 ? message.substring(0, 500) : message));
    }

    private List<SearchResultVO> toResultVO(List<SearchHit> hits) {
        List<SearchResultVO> results = new ArrayList<>();
        for (SearchHit hit : hits) {
            results.add(new SearchResultVO(hit.title(), hit.url(), hit.snippet(),
                    hostOf(hit.url())));
        }
        return results;
    }

    private SearchConfigVO toVO(SearchConfigEntity entity, boolean withKey) {
        SearchProvider provider = SearchProvider.of(entity.getProvider());
        String label = provider == null ? entity.getProvider() : provider.getLabel();
        String description = provider == null ? "" : provider.getDescription();
        return new SearchConfigVO(
                entity.getId(),
                entity.getName(),
                entity.getProvider(),
                label,
                description,
                provider != null && provider.isRequiresKey(),
                normalizeBaseUrl(provider == null ? SearchProvider.BING : provider, entity.getBaseUrl()),
                entity.getApiKeyMask(),
                withKey ? secretCipher.decrypt(entity.getApiKeyCipher()) : null,
                hasText(entity.getApiKeyCipher()),
                entity.getDefaultTopK(),
                entity.getLanguage(),
                entity.getRegion(),
                entity.getTimeoutMs(),
                entity.getEnabled(),
                entity.getIsDefault(),
                entity.getRemark(),
                formatTime(entity.getLastTestAt()),
                entity.getLastTestOk(),
                entity.getLastTestLatencyMs(),
                entity.getLastTestMessage(),
                formatTime(entity.getCreatedAt()));
    }

    private String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception ex) {
            return "";
        }
    }

    private String formatTime(LocalDateTime time) {
        return time == null ? null : TIME_FORMAT.format(time);
    }

    private String readable(Exception ex) {
        String message = ex.getMessage();
        return hasText(message) ? message : "联网搜索失败";
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** 默认搜索源定义 */
    private record DefaultSource(String name, SearchProvider provider, String remark) {
    }
}
