package com.storyforge.infra.ai;

import java.util.Map;

/**
 * 一次 AI 调用的端点快照（由 {@code AiConfig} 解密后组装，仅存在于内存）。
 *
 * <p>{@code temperature} / {@code maxTokens} 允许为空：为空时请求体不带该字段，
 * 以兼容不接受采样参数的推理模型（如 o 系列）。</p>
 *
 * @param baseUrl      接口基址，含版本段，如 {@code https://api.openai.com/v1}
 * @param apiKey       明文 API Key（不落库、不写日志）
 * @param model        模型名
 * @param timeoutMs    单次请求超时
 * @param extraHeaders 附加请求头
 * @param temperature  采样温度，可空
 * @param maxTokens    单次回复上限，可空
 */
public record AiEndpoint(
        String baseUrl,
        String apiKey,
        String model,
        int timeoutMs,
        Map<String, String> extraHeaders,
        Double temperature,
        Integer maxTokens) {

    /** 仅关心连通性 / 模型列表的场景使用的精简构造。 */
    public AiEndpoint(String baseUrl, String apiKey, String model, int timeoutMs, Map<String, String> extraHeaders) {
        this(baseUrl, apiKey, model, timeoutMs, extraHeaders, null, null);
    }

    /** 复制一份并替换模型名，用于场景级模型覆盖。 */
    public AiEndpoint withModel(String anotherModel) {
        return new AiEndpoint(baseUrl, apiKey, anotherModel, timeoutMs, extraHeaders, temperature, maxTokens);
    }
}
