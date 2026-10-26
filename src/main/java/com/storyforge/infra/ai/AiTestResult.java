package com.storyforge.infra.ai;

/**
 * 连通性测试结果。
 *
 * @param ok          是否连通
 * @param latencyMs   耗时（毫秒）
 * @param model       模型回显（服务端实际使用的模型）
 * @param reply       模型回复片段（成功时）
 * @param totalTokens 本次消耗 token（服务端未返回时为 {@code null}）
 * @param error       失败原因（成功时为 {@code null}）
 */
public record AiTestResult(
        boolean ok,
        long latencyMs,
        String model,
        String reply,
        Integer totalTokens,
        String error) {

    public static AiTestResult success(long latencyMs, String model, String reply, Integer totalTokens) {
        return new AiTestResult(true, latencyMs, model, reply, totalTokens, null);
    }

    public static AiTestResult failure(long latencyMs, String error) {
        return new AiTestResult(false, latencyMs, null, null, null, error);
    }
}
