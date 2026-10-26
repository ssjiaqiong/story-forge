package com.storyforge.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.crypto.SecretCipher;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.modules.ai.entity.AiConfigEntity;
import com.storyforge.modules.ai.entity.SceneBindingEntity;
import com.storyforge.modules.ai.mapper.AiConfigMapper;
import com.storyforge.modules.ai.mapper.SceneBindingMapper;
import com.storyforge.modules.ai.scene.AiScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 按场景解析可用的 AI 端点。
 *
 * <p>解析顺序：</p>
 * <ol>
 *   <li>该场景在「设置 → 场景绑定」中绑定的端点（模型 / 温度 / max_tokens 可逐项覆盖）；</li>
 *   <li>绑定不存在或已失效时，回落到该用户的默认端点；</li>
 *   <li>没有默认端点时取第一个启用端点；</li>
 *   <li>一个可用的都没有则抛出 {@code 40002}。</li>
 * </ol>
 *
 * <p>解密后的明文密钥只存在于内存，不写日志。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiSceneEndpointResolver {

    private static final int DEFAULT_TIMEOUT_MS = 60_000;

    private final AiConfigMapper aiConfigMapper;
    private final SceneBindingMapper sceneBindingMapper;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;

    /**
     * 解析某场景应使用的端点。
     *
     * @param scene 场景编码，可为 {@code null} 表示直接用默认端点
     */
    public AiEndpoint resolve(AiScene scene) {
        UserContextHolder.requireUserId();

        SceneBindingEntity binding = scene == null ? null : sceneBindingMapper.selectOne(
                Wrappers.<SceneBindingEntity>lambdaQuery()
                        .eq(SceneBindingEntity::getSceneCode, scene.name())
                        .last("limit 1"));

        AiConfigEntity config = binding == null ? null : aiConfigMapper.selectById(binding.getAiConfigId());
        if (config == null) {
            config = availableConfigs().stream().findFirst().orElse(null);
        }
        if (config == null) {
            throw BizException.of(ErrorCode.AI_CONFIG_MISSING);
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw BizException.of(ErrorCode.AI_CONFIG_DISABLED);
        }
        if ("IMAGE".equalsIgnoreCase(config.getKind())) {
            // 绑错类型时给出明确原因，否则用户只会看到一次莫名其妙的 HTTP 400
            throw BizException.of(ErrorCode.AI_CONFIG_MISSING,
                    "「" + config.getName() + "」是图像接口，不能用于文本生成，请到「设置 → AI 接口管理 → 场景绑定」重新选择");
        }

        String model = binding != null && hasText(binding.getModel())
                ? binding.getModel().trim()
                : config.getDefaultModel();
        if (!hasText(model)) {
            throw BizException.of(ErrorCode.AI_CONFIG_MISSING, "AI 接口「" + config.getName() + "」未配置默认模型");
        }

        Double temperature = binding != null && binding.getTemperature() != null
                ? binding.getTemperature().doubleValue()
                : (config.getTemperature() == null ? null : config.getTemperature().doubleValue());
        Integer maxTokens = binding != null && binding.getMaxTokens() != null
                ? binding.getMaxTokens()
                : config.getMaxTokens();
        int timeout = config.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : config.getTimeoutMs();

        String apiKey;
        try {
            apiKey = secretCipher.decrypt(config.getApiKeyCipher());
        } catch (RuntimeException ex) {
            log.warn("[ai] decrypt api key failed configId={}: {}", config.getId(), ex.getMessage());
            throw BizException.of(ErrorCode.AI_CONFIG_MISSING, "AI 接口密钥无法解密，请到「设置 → AI 接口管理」重新填写");
        }

        return new AiEndpoint(
                config.getBaseUrl(),
                apiKey,
                model,
                timeout,
                parseExtraHeaders(config.getExtraHeaders()),
                temperature,
                maxTokens);
    }

    /** 当前用户可用（启用）的文本端点，默认端点优先。 */
    private List<AiConfigEntity> availableConfigs() {
        return aiConfigMapper.selectList(Wrappers.<AiConfigEntity>lambdaQuery()
                .eq(AiConfigEntity::getKind, "TEXT")
                .eq(AiConfigEntity::getEnabled, true)
                .orderByDesc(AiConfigEntity::getIsDefault)
                .orderByAsc(AiConfigEntity::getCreatedAt));
    }

    private Map<String, String> parseExtraHeaders(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            Map<String, String> headers = objectMapper.readValue(raw, new TypeReference<>() {
            });
            return headers.isEmpty() ? null : headers;
        } catch (Exception ex) {
            log.warn("[ai] ignore invalid extra headers: {}", ex.getMessage());
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
