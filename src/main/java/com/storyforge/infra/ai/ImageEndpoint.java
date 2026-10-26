package com.storyforge.infra.ai;

import com.storyforge.modules.ai.scene.ImageProvider;

/**
 * 一次文生图调用的端点快照（解密后的密钥只在内存中流转）。
 *
 * @param ai               通用端点信息（基址 / 密钥 / 模型 / 超时 / 附加请求头）
 * @param provider         平台协议族
 * @param defaultImageSize 配置里的默认出图尺寸，形如 {@code 1024x1536}
 */
public record ImageEndpoint(AiEndpoint ai, ImageProvider provider, String defaultImageSize) {

    public String baseUrl() {
        return ai.baseUrl();
    }

    public String apiKey() {
        return ai.apiKey();
    }

    public String model() {
        return ai.model();
    }

    public int timeoutMs() {
        return ai.timeoutMs();
    }
}
