package com.storyforge.infra.ai;

/**
 * 一次 chat 调用的结果。
 *
 * @param content          回复正文
 * @param model            服务端实际使用的模型（可能被网关改写）
 * @param promptTokens     输入 token 数（服务端未返回时为 {@code null}）
 * @param completionTokens 输出 token 数（服务端未返回时为 {@code null}）
 * @param latencyMs        端到端耗时
 */
public record ChatResult(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        long latencyMs) {
}
