package com.storyforge.modules.bookshelf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.StreamAbortedException;
import com.storyforge.modules.bookshelf.dto.ContentGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ContentGenerateResultVO;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * AI 写正文的 SSE 推流。
 *
 * <p>事件序列（{@code data} 全是 JSON）：</p>
 * <ol>
 *   <li>{@code meta} —— 检索完成、正文开始写之前，带模型、目标字数、参考资料；</li>
 *   <li>{@code delta} —— 每个增量 {@code {"text":"…"}}；</li>
 *   <li>{@code done} —— 落库与档案补建都完成，载荷与同步接口的返回体完全一致；</li>
 *   <li>{@code error} —— {@code {code, message}}。</li>
 * </ol>
 *
 * <p>为什么不直接占用 Tomcat 工作线程：一章正文要写 40~90 秒，占着请求线程会迅速打满线程池。
 * 这里用独立线程池跑生成，请求线程只负责建好 {@link SseEmitter} 就返回。线程里没有请求的
 * ThreadLocal（用量统计要用它取 user_id），所以先把 {@link UserContext} 捕获进来再 set。</p>
 *
 * <p>断开语义：作者点「停止」或关页面时，{@code send} 会失败 → 抛
 * {@link StreamAbortedException} → 上游连接立刻关闭、<b>正文不落库</b>。
 * 已收到的文本留在前端编辑区当草稿，这正是「中止」应有的结果。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ContentStreamService {

    /** 单条流最长挂 10 分钟：正文本身 5 分钟封顶，剩下的留给档案补建。 */
    private static final long EMITTER_TIMEOUT_MS = 600_000L;
    /** 事件用 UTF-8 写：SSE 默认按 UTF-8 解码，这里显式声明避免中文乱码 */
    private static final MediaType UTF8_TEXT = new MediaType("text", "plain", StandardCharsets.UTF_8);

    private final AiContentService aiContentService;
    private final NovelAssetService assetService;
    private final ObjectMapper objectMapper;

    /** 正在生成的章节 → 发起人。同一章不允许并发两次，否则两段正文会互相覆盖。 */
    private final Map<Long, Long> running = new ConcurrentHashMap<>();

    private final AtomicInteger threadSeq = new AtomicInteger();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "content-stream-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /**
     * 建流并提交生成任务，立即返回 emitter。
     *
     * @throws BizException 本章已有生成在跑（409），或线程池已满
     */
    public SseEmitter start(Long chapterId, ContentGenerateRequest request) {
        UserContext context = UserContextHolder.require();
        // 先做一次存在性与归属校验：章节不存在 / 不是自己的书，应该像其它接口一样直接给 404，
        // 而不是让客户端先拿到一条 200 的流再等一个 error 事件
        assetService.getChapter(chapterId);
        if (running.putIfAbsent(chapterId, context.userId()) != null) {
            throw BizException.of(ErrorCode.CONTENT_GENERATE_RUNNING);
        }

        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        AtomicBoolean closed = new AtomicBoolean(false);
        emitter.onCompletion(() -> closed.set(true));
        emitter.onError(ex -> closed.set(true));
        emitter.onTimeout(() -> {
            closed.set(true);
            log.warn("[content-stream] 超时关闭 chapterId={} userId={}", chapterId, context.userId());
            emitter.complete();
        });

        try {
            executor.submit(() -> run(chapterId, request, context, emitter, closed));
        } catch (RejectedExecutionException ex) {
            running.remove(chapterId, context.userId());
            log.warn("[content-stream] 线程池已满 chapterId={}", chapterId);
            throw BizException.of(ErrorCode.AI_CALL_FAILED, "当前生成任务过多，请等前面的章节写完再试");
        }
        return emitter;
    }

    /** 当前是否有正在推流的章节（自测与运维排查用）。 */
    public int runningCount() {
        return running.size();
    }

    /* ------------------------------------------------------------------ */

    private void run(Long chapterId,
                     ContentGenerateRequest request,
                     UserContext context,
                     SseEmitter emitter,
                     AtomicBoolean closed) {
        UserContextHolder.set(context);
        long startedAt = System.currentTimeMillis();
        try {
            ContentGenerateResultVO result = aiContentService.generateStreaming(chapterId, request,
                    new AiContentService.StreamListener() {
                        @Override
                        public void onMeta(AiContentService.StreamMeta meta) {
                            Map<String, Object> payload = new LinkedHashMap<>();
                            payload.put("model", meta.model());
                            payload.put("targetWords", meta.targetWords());
                            payload.put("mode", meta.mode());
                            payload.put("search", meta.search());
                            send(emitter, closed, "meta", payload);
                        }

                        @Override
                        public void onDelta(String text) {
                            send(emitter, closed, "delta", Map.of("text", text));
                        }
                    });
            send(emitter, closed, "done", result);
            emitter.complete();
            log.info("[content-stream] 完成 chapterId={} userId={} 用时={}ms words={}",
                    chapterId, context.userId(), System.currentTimeMillis() - startedAt, result.generatedWords());
        } catch (StreamAbortedException aborted) {
            // 客户端已断开：send 已经把 closed 置上，completion 由容器回调，这里只留一条日志
            log.info("[content-stream] 客户端中止 chapterId={} userId={} 已用={}ms",
                    chapterId, context.userId(), System.currentTimeMillis() - startedAt);
            emitter.complete();
        } catch (BizException ex) {
            pushError(emitter, closed, chapterId, ex.getErrorCode().getCode(), ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null || ex.getMessage().isBlank()
                    ? "AI 生成失败，请重试" : ex.getMessage();
            pushError(emitter, closed, chapterId, ErrorCode.AI_CALL_FAILED.getCode(), message, ex);
        } finally {
            running.remove(chapterId, context.userId());
            UserContextHolder.clear();
        }
    }

    private void pushError(SseEmitter emitter, AtomicBoolean closed, Long chapterId,
                           int code, String message, RuntimeException ex) {
        log.error("[content-stream] 失败 chapterId={} code={} 原因={}", chapterId, code, ex.toString());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("code", code);
            payload.put("message", message);
            send(emitter, closed, "error", payload);
            emitter.complete();
        } catch (StreamAbortedException ignored) {
            emitter.complete();
        }
    }

    /**
     * 写一帧。客户端已断开时抛 {@link StreamAbortedException}，由上层停止读上游。
     *
     * <p>{@code onError} 与 {@code send} 的 IOException 都会走到这里；一旦判定断开就置
     * {@code closed}，后续 delta 不再尝试写——否则每个 token 都要吃一次异常。</p>
     */
    private void send(SseEmitter emitter, AtomicBoolean closed, String event, Object payload) {
        if (closed.get()) {
            throw new StreamAbortedException("客户端已断开");
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(objectMapper.writeValueAsString(payload), UTF8_TEXT));
        } catch (Exception ex) {
            closed.set(true);
            throw new StreamAbortedException("客户端已断开：" + ex.getClass().getSimpleName(), ex);
        }
    }
}
