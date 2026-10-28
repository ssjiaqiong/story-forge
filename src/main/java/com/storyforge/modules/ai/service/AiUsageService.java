package com.storyforge.modules.ai.service;

import com.storyforge.common.context.UserContextHolder;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.ChatStreamResult;
import com.storyforge.infra.ai.ImageEndpoint;
import com.storyforge.infra.ai.OpenAiClient;
import com.storyforge.infra.ai.StreamAbortedException;
import com.storyforge.modules.ai.entity.AiUsageEntity;
import com.storyforge.modules.ai.scene.AiScene;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 带用量统计的 AI 对话入口。
 *
 * <p>业务代码一律走这里而不是直接调 {@link OpenAiClient#chat}，这样「场景 + token 消耗」
 * 才能落到 {@code t_ai_usage}，仪表盘才拿得到真实数据。埋点失败绝不影响主流程：
 * 统计是附加信息，不能因为它把用户的生成请求搞挂。</p>
 *
 * <p>异步任务（蓝图分段、拆书、章节分析）在线程池里跑，需要先把用户上下文带进去
 * （{@code UserContextHolder.set(...)}），否则拿不到 user_id，这一行会被丢弃。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageService {

    /** 错误信息入库前的最大长度，与列宽一致 */
    private static final int ERROR_MAX_LENGTH = 500;
    /** 一次对话最多尝试次数（首次 + 重试 2 次） */
    private static final int MAX_ATTEMPTS = 3;

    private final OpenAiClient openAiClient;
    private final AiUsageRecorder recorder;

    /**
     * 发起一次对话，并把用量写入流水。
     *
     * <p>失败自动重试（最多重试 {@value #MAX_ATTEMPTS} - 1 次），每次失败打印失败原因与
     * 剩余重试次数；配置类错误（未配 API Key / 模型 / 消息为空）与「请求被中断」不重试。
     * 异常最终原样抛出，由调用方转成业务错误码；每次真实调用都会落一行用量流水。</p>
     */
    public ChatResult chat(AiScene scene, AiEndpoint endpoint, List<ChatMessage> messages) {
        String model = endpoint == null ? null : endpoint.model();
        int messageCount = messages == null ? 0 : messages.size();
        int messageChars = 0;
        if (messages != null) {
            for (ChatMessage message : messages) {
                messageChars += message.content() == null ? 0 : message.content().length();
            }
        }
        log.info("[ai] 请求开始 scene={} model={} baseUrl={} messages={} chars={} temperature={} maxTokens={}",
                sceneLabel(scene), model, endpoint == null ? null : endpoint.baseUrl(), messageCount, messageChars,
                endpoint == null ? null : endpoint.temperature(), endpoint == null ? null : endpoint.maxTokens());

        long startedAt = System.currentTimeMillis();
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ChatResult result = openAiClient.chat(endpoint, messages);
                save(scene, endpoint, result.model(), result.promptTokens(), result.completionTokens(),
                        (int) Math.min(result.latencyMs(), Integer.MAX_VALUE), null);
                log.info("[ai] 请求完成 scene={} model={} latency={}ms tokens=({},{}) 第{}次成功",
                        sceneLabel(scene), result.model(), result.latencyMs(), result.promptTokens(),
                        result.completionTokens(), attempt);
                return result;
            } catch (RuntimeException ex) {
                failure = ex;
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                save(scene, endpoint, model, 0, 0,
                        (int) Math.min(System.currentTimeMillis() - startedAt, Integer.MAX_VALUE), reason);
                if (!isRetryable(reason) || attempt >= MAX_ATTEMPTS) {
                    log.error("[ai] 调用失败 scene={} model={} 原因={} 已尝试{}次", sceneLabel(scene), model, reason, attempt);
                    throw ex;
                }
                log.warn("[ai] 调用失败，自动重试 scene={} model={} 第{}次失败 原因={} 剩余重试{}次",
                        sceneLabel(scene), model, attempt, reason, MAX_ATTEMPTS - attempt);
                sleepQuietly(attempt * 1000L);
            }
        }
        throw failure;
    }

    /** 配置类错误与线程中断重试无意义，直接失败；其余（网络/HTTP/超时/空响应）可重试。 */
    private boolean isRetryable(String message) {
        if (message == null) {
            return true;
        }
        return !(message.contains("未配置 API Key")
                || message.contains("未配置默认模型")
                || message.contains("消息内容为空")
                || message.contains("请求被中断"));
    }

    /**
     * 发起一次流式对话，并把用量写入流水。
     *
     * <p>重试规则与同步版不同：<b>只在吐出第一个增量之前允许重试</b>。一旦前端已经看到
     * 文字，重试就会把开头的段落再吐一遍，作者会看到重复内容——这种「成功但文本错乱」
     * 比直接报错更难处理，所以首字之后一律不再重试。</p>
     *
     * <p>客户端主动中止（{@link StreamAbortedException}）落一条失败流水后原样抛出：
     * 这次生成确实没完成，不该被算进成功率里。</p>
     */
    public ChatStreamResult chatStream(AiScene scene, AiEndpoint endpoint, List<ChatMessage> messages,
                                       Consumer<String> onDelta) {
        return chatStream(scene, endpoint, messages, onDelta, text -> {
        });
    }

    /**
     * 流式对话（带思考增量）。
     *
     * <p>{@code onThinking} 只在推理模型先吐 {@code reasoning_content} 时被调用，用来填补
     * 「请求已发出、正文一个字都还没来」那段几十秒的静默；它和 {@code onDelta} 共用同一套
     * 中止语义——客户端断开时同样抛 {@link StreamAbortedException}。</p>
     */
    public ChatStreamResult chatStream(AiScene scene, AiEndpoint endpoint, List<ChatMessage> messages,
                                       Consumer<String> onDelta, Consumer<String> onThinking) {
        String model = endpoint == null ? null : endpoint.model();
        int messageCount = messages == null ? 0 : messages.size();
        int messageChars = 0;
        if (messages != null) {
            for (ChatMessage message : messages) {
                messageChars += message.content() == null ? 0 : message.content().length();
            }
        }
        log.info("[ai] 流式请求开始 scene={} model={} baseUrl={} messages={} chars={} temperature={} maxTokens={}",
                sceneLabel(scene), model, endpoint == null ? null : endpoint.baseUrl(), messageCount, messageChars,
                endpoint == null ? null : endpoint.temperature(), endpoint == null ? null : endpoint.maxTokens());

        AtomicBoolean emitted = new AtomicBoolean(false);
        Consumer<String> guarded = text -> {
            emitted.set(true);
            onDelta.accept(text);
        };
        // 思考增量同样算「已经吐过东西」：此时重试会让前端看到两段思考，同样属于内容错乱
        Consumer<String> guardedThinking = text -> {
            emitted.set(true);
            onThinking.accept(text);
        };

        long startedAt = System.currentTimeMillis();
        RuntimeException failure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                ChatStreamResult result = openAiClient.chatStream(endpoint, messages, guarded, guardedThinking);
                save(scene, endpoint, result.model(), result.promptTokens(), result.completionTokens(),
                        (int) Math.min(result.latencyMs(), Integer.MAX_VALUE), null);
                log.info("[ai] 流式请求完成 scene={} model={} latency={}ms 首字={}ms 首思考={}ms 增量={} 字={} 思考字={} tokens=({},{}) 第{}次",
                        sceneLabel(scene), result.model(), result.latencyMs(), result.firstTokenMs(), result.firstReasoningMs(),
                        result.deltaCount(), result.content() == null ? 0 : result.content().length(),
                        result.reasoning() == null ? 0 : result.reasoning().length(),
                        result.promptTokens(), result.completionTokens(), attempt);
                return result;
            } catch (StreamAbortedException aborted) {
                int latency = (int) Math.min(System.currentTimeMillis() - startedAt, Integer.MAX_VALUE);
                save(scene, endpoint, model, 0, 0, latency, "客户端已停止生成");
                log.info("[ai] 流式请求被客户端中止 scene={} model={} 已用={}ms 首字前={}",
                        sceneLabel(scene), model, latency, !emitted.get());
                throw aborted;
            } catch (RuntimeException ex) {
                failure = ex;
                String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
                save(scene, endpoint, model, 0, 0,
                        (int) Math.min(System.currentTimeMillis() - startedAt, Integer.MAX_VALUE), reason);
                if (emitted.get()) {
                    log.error("[ai] 流式中途失败，已输出部分内容，不再重试 scene={} model={} 原因={}", sceneLabel(scene), model, reason);
                    throw ex;
                }
                if (!isRetryable(reason) || attempt >= MAX_ATTEMPTS) {
                    log.error("[ai] 流式调用失败 scene={} model={} 原因={} 已尝试{}次", sceneLabel(scene), model, reason, attempt);
                    throw ex;
                }
                log.warn("[ai] 流式调用失败，自动重试 scene={} model={} 第{}次失败 原因={} 剩余重试{}次",
                        sceneLabel(scene), model, attempt, reason, MAX_ATTEMPTS - attempt);
                sleepQuietly(attempt * 1000L);
            }
        }
        throw failure;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 日志里怎么显示这次调用「是干什么的」：场景码 + 中文功能名，例如 {@code BODY_GEN(正文生成)}。
     *
     * <p>场景码是给代码看的，排查线上问题时真正要回答的是「哪项功能在烧 token」，
     * 所以每条调用日志都带上中文名。</p>
     */
    private String sceneLabel(AiScene scene) {
        return scene == null ? "-" : scene.name() + "(" + scene.getLabel() + ")";
    }

    /**
     * 记录一次文生图调用。
     *
     * <p>图片按张计费、服务端不回 token，因此 token 一律记 0——只保留「什么时候调了哪个模型、
     * 花了多久、成功与否」，不编造不存在的消耗数字。</p>
     */
    public void recordImage(AiScene scene, ImageEndpoint endpoint, String model, int latencyMs, String error) {
        save(scene, endpoint == null ? null : endpoint.ai(), model, 0, 0, latencyMs, error);
    }

    /**
     * 记录一次已完成调用。
     *
     * <p>模型名优先取服务端返回的实际值（网关可能改写），取不到退回配置里的模型。</p>
     */
    private void save(AiScene scene, AiEndpoint endpoint, String model,
                      Integer promptTokens, Integer completionTokens, Integer latencyMs, String error) {
        Long userId = UserContextHolder.getUserIdOrNull();
        if (userId == null) {
            // 没有用户上下文（系统任务）时无从归属，直接跳过
            return;
        }
        try {
            AiUsageEntity entity = new AiUsageEntity();
            entity.setUserId(userId);
            entity.setScene(scene == null ? "UNKNOWN" : scene.name());
            entity.setModel(model == null ? (endpoint == null ? null : endpoint.model()) : model);
            int prompt = promptTokens == null ? 0 : Math.max(promptTokens, 0);
            int completion = completionTokens == null ? 0 : Math.max(completionTokens, 0);
            entity.setPromptTokens(prompt);
            entity.setCompletionTokens(completion);
            entity.setTotalTokens(prompt + completion);
            entity.setLatencyMs(latencyMs);
            entity.setSuccess(error == null ? 1 : 0);
            entity.setErrorMessage(error == null ? null : truncate(error));
            recorder.save(entity);
        } catch (RuntimeException ex) {
            log.warn("[ai-usage] record failed scene={} userId={}: {}", sceneLabel(scene), userId, ex.toString());
        }
    }

    private String truncate(String value) {
        String trimmed = value.trim();
        return trimmed.length() <= ERROR_MAX_LENGTH ? trimmed : trimmed.substring(0, ERROR_MAX_LENGTH) + "…";
    }
}
