package com.storyforge.infra.ai;

/**
 * 流式读取被下游主动中止。
 *
 * <p>典型场景：作者点「停止」或直接关掉页面，SSE 连接断开后 {@code onDelta} 写不出去。
 * 抛出它而不是 {@link IllegalStateException}，是为了让上层能一眼区分「AI 失败」与
 * 「客户端不要了」——后者不该重试，也不该把已生成的文本落库。</p>
 */
public class StreamAbortedException extends RuntimeException {

    public StreamAbortedException(String message) {
        super(message);
    }

    public StreamAbortedException(String message, Throwable cause) {
        super(message, cause);
    }
}
