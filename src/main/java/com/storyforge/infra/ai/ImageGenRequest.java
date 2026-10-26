package com.storyforge.infra.ai;

/**
 * 文生图请求。
 *
 * @param prompt         正向提示词（必填）
 * @param negativePrompt 负面提示词，平台不支持时忽略
 * @param size           出图尺寸，形如 {@code 1024x1536}；为空时用端点默认值
 */
public record ImageGenRequest(String prompt, String negativePrompt, String size) {
}
