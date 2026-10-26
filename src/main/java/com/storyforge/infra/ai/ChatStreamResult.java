package com.storyforge.infra.ai;

/**
 * 流式对话结果。
 *
 * <p>{@code content} 是已经拼好的完整文本；{@code firstTokenMs} 用于观察上游首字延迟，
 * 只打日志、不进流水表（避免为统计加列）。{@code deltaCount} 为 0 时说明上游没按 SSE 返回，
 * 调用方据此决定是否降级成同步请求。</p>
 *
 * @param content          累加后的完整文本
 * @param model            上游返回的实际模型名，取不到时退回配置值
 * @param firstTokenMs     从发请求到收到首个增量的毫秒数，没收到时为 -1
 * @param latencyMs        整个流读取耗时
 * @param promptTokens     提示词 token，上游没给时为 {@code null}
 * @param completionTokens 生成 token，上游没给时为 {@code null}
 * @param deltaCount       收到的增量条数
 */
public record ChatStreamResult(
        String content,
        String model,
        long firstTokenMs,
        long latencyMs,
        Integer promptTokens,
        Integer completionTokens,
        int deltaCount) {

    /** 上游完全没吐增量：既不是流式协议，也没有可用的整段回复。 */
    public boolean empty() {
        return deltaCount <= 0 || content == null || content.isBlank();
    }
}
