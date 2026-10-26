package com.storyforge.modules.ai.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.crypto.SecretCipher;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ImageEndpoint;
import com.storyforge.modules.ai.entity.AiConfigEntity;
import com.storyforge.modules.ai.entity.SceneBindingEntity;
import com.storyforge.modules.ai.mapper.AiConfigMapper;
import com.storyforge.modules.ai.mapper.SceneBindingMapper;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.scene.ImageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 解析文生图端点。
 *
 * <p>与文本链路分开的原因：文本接口有「默认端点」兜底，图像接口不能沿用——用户很可能只配过一个
 * 文本接口，如果图像也回落到它，只会得到一次 404。这里只认图像类型（{@code kind = IMAGE}）的配置。</p>
 *
 * <p>解析顺序：场景绑定的图像接口 → 默认图像接口 → 第一个启用的图像接口 → 抛出 {@code 40053}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageEndpointResolver {

    private static final int DEFAULT_TIMEOUT_MS = 180_000;

    private final AiConfigMapper aiConfigMapper;
    private final SceneBindingMapper sceneBindingMapper;
    private final SecretCipher secretCipher;
    private final ObjectMapper objectMapper;

    /**
     * 解析封面生成要用的端点。
     *
     * @param scene 场景编码，传 {@code null} 表示直接用默认图像接口
     */
    public ImageEndpoint resolve(AiScene scene) {
        UserContextHolder.requireUserId();

        SceneBindingEntity binding = scene == null ? null : sceneBindingMapper.selectOne(
                Wrappers.<SceneBindingEntity>lambdaQuery()
                        .eq(SceneBindingEntity::getSceneCode, scene.name())
                        .last("limit 1"));

        AiConfigEntity config = binding == null ? null : aiConfigMapper.selectById(binding.getAiConfigId());
        if (config != null && !isImage(config)) {
            throw BizException.of(ErrorCode.AI_CONFIG_KIND_MISMATCH,
                    "「" + config.getName() + "」是文本接口，封面生成需要图像接口，请到「设置 → AI 接口管理 → 场景绑定」重新选择");
        }
        if (config == null) {
            config = imageConfigs().stream().findFirst().orElse(null);
        }
        if (config == null) {
            throw BizException.of(ErrorCode.COVER_CONFIG_MISSING);
        }
        if (Boolean.FALSE.equals(config.getEnabled())) {
            throw BizException.of(ErrorCode.AI_CONFIG_DISABLED);
        }

        String model = binding != null && hasText(binding.getModel())
                ? binding.getModel().trim()
                : config.getDefaultModel();
        if (!hasText(model)) {
            throw BizException.of(ErrorCode.COVER_CONFIG_MISSING,
                    "图像接口「" + config.getName() + "」未配置默认模型");
        }

        String apiKey;
        try {
            apiKey = secretCipher.decrypt(config.getApiKeyCipher());
        } catch (RuntimeException ex) {
            log.warn("[image] decrypt api key failed configId={}: {}", config.getId(), ex.getMessage());
            throw BizException.of(ErrorCode.COVER_CONFIG_MISSING,
                    "图像接口密钥无法解密，请到「设置 → AI 接口管理」重新填写");
        }

        ImageProvider provider = ImageProvider.of(config.getProvider());
        if (provider == null) {
            provider = ImageProvider.OPENAI;
        }
        AiEndpoint ai = new AiEndpoint(
                config.getBaseUrl(),
                apiKey,
                model,
                config.getTimeoutMs() == null ? DEFAULT_TIMEOUT_MS : config.getTimeoutMs(),
                parseExtraHeaders(config.getExtraHeaders()));
        return new ImageEndpoint(ai, provider, config.getImageSize());
    }

    /** 当前用户的图像接口，默认图像接口优先。 */
    private List<AiConfigEntity> imageConfigs() {
        return aiConfigMapper.selectList(Wrappers.<AiConfigEntity>lambdaQuery()
                .eq(AiConfigEntity::getKind, "IMAGE")
                .eq(AiConfigEntity::getEnabled, true)
                .orderByDesc(AiConfigEntity::getIsDefault)
                .orderByAsc(AiConfigEntity::getCreatedAt));
    }

    private boolean isImage(AiConfigEntity config) {
        return "IMAGE".equalsIgnoreCase(config.getKind());
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
            log.warn("[image] ignore invalid extra headers: {}", ex.getMessage());
            return null;
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
