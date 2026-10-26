package com.storyforge.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.crypto.SecretCipher;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.AiTestResult;
import com.storyforge.infra.ai.OpenAiClient;
import com.storyforge.modules.ai.dto.AiConfigCreateRequest;
import com.storyforge.modules.ai.dto.AiConfigUpdateRequest;
import com.storyforge.modules.ai.dto.AiConfigVO;
import com.storyforge.modules.ai.dto.AiModelProbeRequest;
import com.storyforge.modules.ai.dto.ImageProviderPresetVO;
import com.storyforge.modules.ai.entity.AiConfigEntity;
import com.storyforge.modules.ai.entity.SceneBindingEntity;
import com.storyforge.modules.ai.mapper.AiConfigMapper;
import com.storyforge.modules.ai.mapper.SceneBindingMapper;
import com.storyforge.modules.ai.scene.AiConfigKind;
import com.storyforge.modules.ai.scene.ImageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 接口配置服务实现。
 *
 * <p>不变量：</p>
 * <ol>
 *   <li>同一用户内配置名称唯一；「默认端点」按类型各一个——文本接口与图像接口互不顶替；</li>
 *   <li>默认端点必须处于启用状态——停用默认端点会自动清除默认标记；</li>
 *   <li>API Key 仅以密文落库，出参只回显掩码；</li>
 *   <li>被场景绑定的端点不允许删除，需先解除绑定（避免静默丢失绑定）；</li>
 *   <li>接口类型（文本 / 图像）创建后不可变更——它决定了调用协议，改了等于换了一个接口。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiConfigServiceImpl implements AiConfigService {

    private static final int DEFAULT_TIMEOUT_MS = 60_000;
    private static final double DEFAULT_TEMPERATURE = 0.8D;
    private static final int DEFAULT_MAX_TOKENS = 4096;
    private static final double DEFAULT_TOP_P = 1.0D;
    /** 图像接口默认超时：出图普遍比对话慢，至少给 60 秒 */
    private static final int DEFAULT_IMAGE_TIMEOUT_MS = 180_000;
    private static final String SIZE_PATTERN = "^\\d{3,4}x\\d{3,4}$";

    private final AiConfigMapper aiConfigMapper;
    private final SceneBindingMapper sceneBindingMapper;
    private final SecretCipher secretCipher;
    private final OpenAiClient openAiClient;
    private final ObjectMapper objectMapper;

    @Override
    public List<AiConfigVO> list() {
        List<AiConfigEntity> entities = aiConfigMapper.selectList(Wrappers.<AiConfigEntity>lambdaQuery()
                .orderByDesc(AiConfigEntity::getIsDefault)
                .orderByAsc(AiConfigEntity::getCreatedAt));
        return entities.stream()
                .map(entity -> AiConfigVO.from(entity, parseModelList(entity.getModelList())))
                .toList();
    }

    @Override
    public AiConfigVO detail(Long id) {
        // 详情用于编辑页回填：解密出明文交给前端密码框，可切换查看
        return toVO(requireConfig(id), true);
    }

    /** 写操作（新增/编辑/设为默认/启停）的响应：只回显掩码，不回传明文。 */
    private AiConfigVO detailWithoutSecret(Long id) {
        return toVO(requireConfig(id), false);
    }

    private AiConfigVO toVO(AiConfigEntity entity, boolean revealApiKey) {
        AiConfigVO vo = AiConfigVO.from(entity, parseModelList(entity.getModelList()));
        if (revealApiKey) {
            vo.setApiKey(revealApiKey(entity));
        }
        return vo;
    }

    /**
     * 解密密钥供编辑页回显。
     *
     * <p>主密钥被更换或密文损坏时降级为 {@code null}（界面只剩掩码），
     * 不因单条配置无法解密而阻断详情访问。</p>
     */
    private String revealApiKey(AiConfigEntity entity) {
        try {
            return secretCipher.decrypt(entity.getApiKeyCipher());
        } catch (RuntimeException ex) {
            log.warn("[ai-config] reveal api key failed id={}: {}", entity.getId(), ex.getMessage());
            return null;
        }
    }

    @Override
    public List<ImageProviderPresetVO> presets() {
        return Arrays.stream(ImageProvider.values()).map(ImageProviderPresetVO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiConfigVO create(AiConfigCreateRequest request) {
        Long userId = UserContextHolder.requireUserId();
        String name = request.getName().trim();
        ensureNameUnique(name);

        AiConfigKind kind = parseKind(request.getKind());
        ImageProvider provider = kind == AiConfigKind.IMAGE
                ? parseProvider(request.getProvider(), true)
                : ImageProvider.OPENAI;
        String imageSize = kind == AiConfigKind.IMAGE
                ? normalizeImageSize(request.getImageSize(), provider)
                : null;

        // 「首个配置自动成为默认」按类型分别判断：配了图像接口不会顶掉已有的文本默认端点
        boolean firstOfKind = aiConfigMapper.selectCount(Wrappers.<AiConfigEntity>lambdaQuery()
                .eq(AiConfigEntity::getKind, kind.name())) == 0L;
        String apiKey = request.getApiKey().trim();

        AiConfigEntity entity = new AiConfigEntity();
        entity.setUserId(userId);
        entity.setName(name);
        entity.setKind(kind.name());
        entity.setProvider(provider.name());
        entity.setImageSize(imageSize);
        entity.setBaseUrl(normalizeBaseUrl(request.getBaseUrl()));
        entity.setApiKeyCipher(secretCipher.encrypt(apiKey));
        entity.setApiKeyMask(secretCipher.mask(apiKey));
        entity.setDefaultModel(request.getDefaultModel().trim());
        entity.setTimeoutMs(request.getTimeoutMs() == null
                ? (kind == AiConfigKind.IMAGE ? DEFAULT_IMAGE_TIMEOUT_MS : DEFAULT_TIMEOUT_MS)
                : request.getTimeoutMs());
        entity.setTemperature(request.getTemperature() == null ? DEFAULT_TEMPERATURE : request.getTemperature());
        entity.setMaxTokens(request.getMaxTokens() == null ? DEFAULT_MAX_TOKENS : request.getMaxTokens());
        entity.setTopP(request.getTopP() == null ? DEFAULT_TOP_P : request.getTopP());
        entity.setExtraHeaders(normalizeExtraHeaders(request.getExtraHeaders()));
        entity.setEnabled(request.getEnabled() == null || request.getEnabled());
        entity.setIsDefault(false);
        entity.setRemark(blankToNull(request.getRemark()));
        entity.setDeleted(0);
        entity.setVersion(0);
        aiConfigMapper.insert(entity);

        boolean makeDefault = firstOfKind || Boolean.TRUE.equals(request.getIsDefault());
        if (makeDefault && entity.getEnabled()) {
            applyDefault(entity.getId());
        }
        log.info("[ai-config] created id={} userId={} name={} kind={} provider={} default={}",
                entity.getId(), userId, name, kind, provider, makeDefault);
        return detailWithoutSecret(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiConfigVO update(Long id, AiConfigUpdateRequest request) {
        AiConfigEntity target = requireConfig(id);
        AiConfigEntity patch = new AiConfigEntity();
        patch.setId(id);
        boolean changed = false;

        if (request.getName() != null && !request.getName().trim().equals(target.getName())) {
            String name = request.getName().trim();
            ensureNameUnique(name);
            patch.setName(name);
            changed = true;
        }
        if (request.getBaseUrl() != null) {
            String baseUrl = normalizeBaseUrl(request.getBaseUrl());
            if (!baseUrl.equals(target.getBaseUrl())) {
                patch.setBaseUrl(baseUrl);
                changed = true;
            }
        }
        if (request.getProvider() != null) {
            // 文本接口固定走 OpenAI 协议，忽略 provider，避免用户误以为改了协议
            if (isImage(target)) {
                ImageProvider provider = parseProvider(request.getProvider(), true);
                if (!provider.name().equals(target.getProvider())) {
                    patch.setProvider(provider.name());
                    changed = true;
                }
            }
        }
        if (request.getImageSize() != null) {
            if (isImage(target)) {
                String size = normalizeImageSize(request.getImageSize(), parseProvider(target.getProvider(), false));
                if (size != null) {
                    patch.setImageSize(size);
                    changed = true;
                }
            }
        }
        if (request.getApiKey() != null && !request.getApiKey().isBlank()) {
            String apiKey = request.getApiKey().trim();
            patch.setApiKeyCipher(secretCipher.encrypt(apiKey));
            patch.setApiKeyMask(secretCipher.mask(apiKey));
            changed = true;
        }
        if (request.getDefaultModel() != null && !request.getDefaultModel().isBlank()) {
            patch.setDefaultModel(request.getDefaultModel().trim());
            changed = true;
        }
        if (request.getTimeoutMs() != null) {
            patch.setTimeoutMs(request.getTimeoutMs());
            changed = true;
        }
        if (request.getTemperature() != null) {
            patch.setTemperature(request.getTemperature());
            changed = true;
        }
        if (request.getMaxTokens() != null) {
            patch.setMaxTokens(request.getMaxTokens());
            changed = true;
        }
        if (request.getTopP() != null) {
            patch.setTopP(request.getTopP());
            changed = true;
        }
        if (request.getExtraHeaders() != null) {
            String headers = normalizeExtraHeaders(request.getExtraHeaders());
            // 用空串表示「清空」，null 会被 MyBatis-Plus 跳过而无法清空
            patch.setExtraHeaders(headers == null ? "" : headers);
            changed = true;
        }
        if (request.getRemark() != null) {
            // 允许保存空串以清空备注
            patch.setRemark(request.getRemark().trim());
            changed = true;
        }
        boolean disablingDefault = Boolean.FALSE.equals(request.getEnabled())
                && Boolean.TRUE.equals(target.getIsDefault());
        if (request.getEnabled() != null) {
            patch.setEnabled(request.getEnabled());
            if (disablingDefault) {
                // 与「停用」操作保持同一不变量：默认端点必须启用
                patch.setIsDefault(false);
            }
            changed = true;
        }

        if (!changed) {
            return detailWithoutSecret(id);
        }
        patch.setVersion(target.getVersion());
        aiConfigMapper.updateById(patch);

        if (disablingDefault) {
            promoteFallbackDefault(target.getKind());
        } else if (Boolean.TRUE.equals(request.getEnabled())) {
            ensureDefaultExists(id, target.getKind());
        }
        log.info("[ai-config] updated id={} userId={} enabled={}", id, target.getUserId(), request.getEnabled());
        return detailWithoutSecret(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        AiConfigEntity target = requireConfig(id);

        Long bound = sceneBindingMapper.selectCount(Wrappers.<SceneBindingEntity>lambdaQuery()
                .eq(SceneBindingEntity::getAiConfigId, id));
        if (bound != null && bound > 0) {
            throw BizException.of(ErrorCode.AI_CONFIG_IN_USE,
                    "该接口已被 " + bound + " 个场景绑定，请先在「场景绑定」中解除");
        }

        boolean wasDefault = Boolean.TRUE.equals(target.getIsDefault());
        aiConfigMapper.deleteById(id);
        if (wasDefault) {
            promoteFallbackDefault(target.getKind());
        }
        log.info("[ai-config] deleted id={} userId={} wasDefault={}", id, target.getUserId(), wasDefault);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiConfigVO setDefault(Long id) {
        AiConfigEntity target = requireConfig(id);
        if (!Boolean.TRUE.equals(target.getEnabled())) {
            throw BizException.of(ErrorCode.AI_CONFIG_DISABLED, "请先启用该接口再设为默认");
        }
        applyDefault(id);
        log.info("[ai-config] set default id={} userId={}", id, target.getUserId());        return detailWithoutSecret(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiConfigVO changeEnabled(Long id, boolean enabled) {
        AiConfigEntity target = requireConfig(id);
        AiConfigEntity patch = new AiConfigEntity();
        patch.setId(id);
        patch.setEnabled(enabled);
        // 停用默认端点：同时取消其默认标记，保证「默认必启用」不变量
        if (!enabled && Boolean.TRUE.equals(target.getIsDefault())) {
            patch.setIsDefault(false);
        }
        patch.setVersion(target.getVersion());
        aiConfigMapper.updateById(patch);

        if (enabled) {
            // 当前该类型还没有默认端点时，顺手补位
            ensureDefaultExists(id, target.getKind());
        }
        log.info("[ai-config] enabled={} id={} userId={}", enabled, id, target.getUserId());
        return detailWithoutSecret(id);
    }

    @Override
    public AiTestResult test(Long id) {
        AiConfigEntity entity = requireConfig(id);
        String apiKey = secretCipher.decrypt(entity.getApiKeyCipher());
        AiEndpoint endpoint = new AiEndpoint(
                entity.getBaseUrl(),
                apiKey,
                entity.getDefaultModel(),
                entity.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : entity.getTimeoutMs(),
                parseExtraHeaders(entity.getExtraHeaders()));

        AiTestResult result = isImage(entity) ? testImage(entity, endpoint) : openAiClient.testChat(endpoint);
        recordTestResult(id, result);
        log.info("[ai-config] tested id={} kind={} ok={} latency={}ms",
                id, entity.getKind(), result.ok(), result.latencyMs());
        return result;
    }

    /**
     * 图像接口的连通性测试。
     *
     * <p>文生图没有免费的「hello world」——真发一次请求就会产生费用，所以这里用
     * {@code GET /models} 验证地址与密钥（能列出模型说明鉴权通过）。阿里云百炼没有该接口，
     * 直接返回带说明的失败信息，提示用户跳过测试直接生成。</p>
     */
    private AiTestResult testImage(AiConfigEntity entity, AiEndpoint endpoint) {
        ImageProvider provider = parseProvider(entity.getProvider(), false);
        if (!provider.isSupportsModelList()) {
            return AiTestResult.failure(0L, provider.getLabel() + " 不提供模型列表接口，无法在线探测；"
                    + "可直接到书架生成封面验证（首次调用即校验密钥）");
        }
        long startedAt = System.currentTimeMillis();
        try {
            List<String> models = openAiClient.listModels(endpoint, modelQuery(entity, provider));
            long latency = System.currentTimeMillis() - startedAt;
            String reply = models.isEmpty() ? "鉴权通过（模型列表为空）"
                    : "鉴权通过，该接口提供 " + models.size() + " 个模型";
            return AiTestResult.success(latency, entity.getDefaultModel(), reply, null);
        } catch (RuntimeException ex) {
            return AiTestResult.failure(System.currentTimeMillis() - startedAt, ex.getMessage());
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> fetchModels(Long id) {
        AiConfigEntity entity = requireConfig(id);
        AiEndpoint endpoint = new AiEndpoint(
                entity.getBaseUrl(),
                secretCipher.decrypt(entity.getApiKeyCipher()),
                entity.getDefaultModel(),
                entity.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : entity.getTimeoutMs(),
                parseExtraHeaders(entity.getExtraHeaders()));

        List<String> models = listModels(endpoint, modelQuery(entity, parseProvider(entity.getProvider(), false)));
        cacheModels(entity, models);
        log.info("[ai-config] fetched {} models id={}", models.size(), id);
        return models;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<String> probeModels(AiModelProbeRequest request) {
        AiConfigEntity existing = request.getId() == null ? null : requireConfig(request.getId());
        String apiKey = request.getApiKey() == null ? null : request.getApiKey().trim();
        if (apiKey == null || apiKey.isEmpty()) {
            if (existing == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "请先填写 API Key 再拉取模型");
            }
            // 编辑场景密钥尚未回填成功：退回使用已存密文
            apiKey = secretCipher.decrypt(existing.getApiKeyCipher());
        }
        String extraHeaders = request.getExtraHeaders() != null
                ? request.getExtraHeaders()
                : (existing == null ? null : existing.getExtraHeaders());
        AiEndpoint endpoint = new AiEndpoint(
                normalizeBaseUrl(request.getBaseUrl()),
                apiKey,
                existing == null ? null : existing.getDefaultModel(),
                request.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : request.getTimeoutMs(),
                parseExtraHeaders(extraHeaders));

        // 新建时表单尚未落库，用请求里的 kind / provider 判断是否需要按图像类型筛选
        AiConfigKind kind = request.getKind() == null
                ? (existing == null ? AiConfigKind.TEXT : parseKind(existing.getKind()))
                : parseKind(request.getKind());
        ImageProvider provider = request.getProvider() != null
                ? parseProvider(request.getProvider(), false)
                : parseProvider(existing == null ? null : existing.getProvider(), false);
        if (kind == AiConfigKind.IMAGE && !provider.isSupportsModelList()) {
            throw BizException.of(ErrorCode.AI_MODEL_LIST_FAILED,
                    provider.getLabel() + " 不提供模型列表接口，请手动填写模型名");
        }

        String query = kind == AiConfigKind.IMAGE && provider == ImageProvider.SILICONFLOW ? "type=image" : null;
        List<String> models = listModels(endpoint, query);
        if (existing != null) {
            cacheModels(existing, models);
        }
        log.info("[ai-config] probed {} models baseUrl={} id={}",
                models.size(), endpoint.baseUrl(), request.getId());
        return models;
    }

    /** 调 {@code {baseUrl}/models}，失败统一收敛为 50005。 */
    private List<String> listModels(AiEndpoint endpoint, String query) {
        try {
            return openAiClient.listModels(endpoint, query);
        } catch (RuntimeException ex) {
            throw BizException.of(ErrorCode.AI_MODEL_LIST_FAILED, ex.getMessage());
        }
    }

    /** 图像接口按平台追加模型筛选参数：目前只有硅基流动支持 {@code type=image}。 */
    private String modelQuery(AiConfigEntity entity, ImageProvider provider) {
        if (!isImage(entity)) {
            return null;
        }
        return provider == ImageProvider.SILICONFLOW ? "type=image" : null;
    }

    /** 把模型列表写入该配置的缓存字段。 */
    private void cacheModels(AiConfigEntity entity, List<String> models) {
        AiConfigEntity patch = new AiConfigEntity();
        patch.setId(entity.getId());
        patch.setModelList(writeJson(models));
        patch.setVersion(entity.getVersion());
        aiConfigMapper.updateById(patch);
    }

    /* ------------------------------------------------------------------ */

    /** 解析接口类型；非法值直接 400，避免落库脏数据。 */
    private AiConfigKind parseKind(String raw) {
        if (raw == null || raw.isBlank()) {
            return AiConfigKind.TEXT;
        }
        AiConfigKind kind = AiConfigKind.of(raw);
        if (kind == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "接口类型只能是 TEXT（文本）或 IMAGE（图像）");
        }
        return kind;
    }

    /** 解析平台协议；{@code strict} 时非法值报错，否则回落到 OpenAI 兼容协议。 */
    private ImageProvider parseProvider(String raw, boolean strict) {
        if (raw == null || raw.isBlank()) {
            return ImageProvider.OPENAI;
        }
        ImageProvider provider = ImageProvider.of(raw);
        if (provider == null) {
            if (strict) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "不支持的图像平台：" + raw);
            }
            return ImageProvider.OPENAI;
        }
        return provider;
    }

    /** 校验出图尺寸：只接受「宽x高」，为空或非法时回落到平台推荐值。 */
    private String normalizeImageSize(String raw, ImageProvider provider) {
        String fallback = provider == null ? null : provider.getDefaultImageSize();
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim().toLowerCase().replace('*', 'x');
        if (!value.matches(SIZE_PATTERN)) {
            if (raw.isBlank()) {
                return fallback;
            }
            throw BizException.of(ErrorCode.PARAM_INVALID, "出图尺寸格式应为「宽x高」，如 1024x1536");
        }
        return value;
    }

    private boolean isImage(AiConfigEntity entity) {
        return AiConfigKind.IMAGE.name().equalsIgnoreCase(entity.getKind());
    }

    private String normalizeKind(String kind) {
        return kind == null || kind.isBlank() ? AiConfigKind.TEXT.name() : kind.toUpperCase();
    }

    private AiConfigEntity requireConfig(Long id) {
        AiConfigEntity entity = aiConfigMapper.selectById(id);
        if (entity == null) {
            // 跨用户访问与真正不存在返回同一结果，不泄露资源存在性
            throw BizException.of(ErrorCode.AI_CONFIG_NOT_FOUND);
        }
        return entity;
    }

    private void ensureNameUnique(String name) {
        Long count = aiConfigMapper.selectCount(Wrappers.<AiConfigEntity>lambdaQuery()
                .eq(AiConfigEntity::getName, name));
        if (count != null && count > 0) {
            throw BizException.of(ErrorCode.AI_CONFIG_NAME_EXISTS);
        }
    }

    /** 把指定配置设为默认：先清空同用户同类型的其他默认标记，再置位。 */
    private void applyDefault(Long id) {
        AiConfigEntity target = aiConfigMapper.selectById(id);
        if (target == null) {
            return;
        }
        String kind = target.getKind() == null ? AiConfigKind.TEXT.name() : target.getKind();
        Long userId = UserContextHolder.requireUserId();
        aiConfigMapper.update(null, Wrappers.<AiConfigEntity>lambdaUpdate()
                .eq(AiConfigEntity::getUserId, userId)
                .eq(AiConfigEntity::getKind, kind)
                .eq(AiConfigEntity::getIsDefault, true)
                .ne(AiConfigEntity::getId, id)
                .set(AiConfigEntity::getIsDefault, false)
                .set(AiConfigEntity::getUpdatedAt, LocalDateTime.now())
                .set(AiConfigEntity::getUpdatedBy, userId));

        AiConfigEntity patch = new AiConfigEntity();
        patch.setId(id);
        patch.setIsDefault(true);
        aiConfigMapper.updateById(patch);
    }

    /** 该类型还没有默认端点时，把 {@code candidateId} 补位为默认。 */
    private void ensureDefaultExists(Long candidateId, String kind) {
        long defaultCount = aiConfigMapper.selectCount(Wrappers.<AiConfigEntity>lambdaQuery()
                .eq(AiConfigEntity::getKind, normalizeKind(kind))
                .eq(AiConfigEntity::getIsDefault, true));
        if (defaultCount == 0L) {
            applyDefault(candidateId);
        }
    }

    /** 删除 / 停用默认端点后，从同类型剩余启用端点中挑一个补位。 */
    private void promoteFallbackDefault(String kind) {
        Long userId = UserContextHolder.requireUserId();
        String normalized = normalizeKind(kind);
        List<AiConfigEntity> candidates = aiConfigMapper.selectList(Wrappers.<AiConfigEntity>lambdaQuery()
                .eq(AiConfigEntity::getKind, normalized)
                .eq(AiConfigEntity::getEnabled, true)
                .orderByAsc(AiConfigEntity::getCreatedAt));
        if (candidates.isEmpty()) {
            return;
        }
        Long nextId = candidates.get(0).getId();
        aiConfigMapper.update(null, Wrappers.<AiConfigEntity>lambdaUpdate()
                .eq(AiConfigEntity::getUserId, userId)
                .eq(AiConfigEntity::getKind, normalized)
                .eq(AiConfigEntity::getIsDefault, true)
                .set(AiConfigEntity::getIsDefault, false));

        AiConfigEntity patch = new AiConfigEntity();
        patch.setId(nextId);
        patch.setIsDefault(true);
        aiConfigMapper.updateById(patch);
        log.info("[ai-config] promoted fallback default id={} kind={}", nextId, normalized);
    }

    private void recordTestResult(Long id, AiTestResult result) {
        aiConfigMapper.update(null, Wrappers.<AiConfigEntity>lambdaUpdate()
                .eq(AiConfigEntity::getId, id)
                .set(AiConfigEntity::getLastTestAt, LocalDateTime.now())
                .set(AiConfigEntity::getLastTestOk, result.ok())
                .set(AiConfigEntity::getLastTestLatencyMs, (int) Math.min(result.latencyMs(), Integer.MAX_VALUE)));
    }

    /** 规整接口基址：去空白、去尾部斜杠、容忍误贴的 /chat/completions 后缀。 */
    private String normalizeBaseUrl(String raw) {
        String base = raw == null ? "" : raw.trim();
        if (base.endsWith("/chat/completions")) {
            base = base.substring(0, base.length() - "/chat/completions".length());
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** 校验并规整附加请求头：必须是 JSON 对象，值转为字符串。 */
    private String normalizeExtraHeaders(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (!node.isObject()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "附加请求头必须是 JSON 对象，如 {\"X-Org\":\"xxx\"}");
            }
            Map<String, String> headers = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> {
                JsonNode value = entry.getValue();
                if (value != null && !value.isNull()) {
                    headers.put(entry.getKey(), value.asText());
                }
            });
            return headers.isEmpty() ? null : objectMapper.writeValueAsString(headers);
        } catch (BizException ex) {
            throw ex;
        } catch (Exception ex) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "附加请求头不是合法 JSON：" + ex.getMessage());
        }
    }

    private Map<String, String> parseExtraHeaders(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception ex) {
            log.warn("[ai-config] invalid extra_headers stored: {}", raw);
            return Map.of();
        }
    }

    private List<String> parseModelList(String raw) {
        if (raw == null || raw.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return null;
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
