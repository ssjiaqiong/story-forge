package com.storyforge.modules.bookshelf.service;

import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.ContentGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ContentGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.WriteTaskChapterVO;
import com.storyforge.modules.bookshelf.dto.WriteTaskRequest;
import com.storyforge.modules.bookshelf.dto.WriteTaskVO;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 批量连写 N 章。
 *
 * <p>为什么是「内存任务 + 前端轮询」而不是 SSE 或 WebSocket：一次连写要跑 N 分钟，
 * 用长连接要么占着 N 条 SSE 连接，要么得处理断线续看。这里沿用 {@code BlueprintTaskService}
 * 已经跑通的模式（{@code ConcurrentHashMap} + 独立线程池 + TTL 30 分钟 + 前端定时拉状态），
 * 代价是进程重启会丢任务——和现有的蓝图任务、拆书任务一致，用户可以重发起。</p>
 *
 * <p>为什么<b>串行</b>写：正文生成会重新读全书章节把前面几章的结尾与前情塞进上下文，
 * 下一章必须读到上一章刚写进去的正文和补建的档案，否则剧情接不上。并行等于各自写各自的。</p>
 *
 * <p>为什么<b>不用流式</b>：N 条 SSE 长连接没什么收益，连写的重点是「不用守着」而不是即时看字。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterWriteTaskService {

    /** 单次连写章数上限：超过这个数，一次请求要跑十几分钟，失败重来的代价太大 */
    public static final int MAX_COUNT = 10;
    private static final int DEFAULT_TARGET_WORDS = 2500;
    private static final long TASK_TTL_MS = 30 * 60 * 1000L;

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private static final String ITEM_PENDING = "PENDING";
    private static final String ITEM_RUNNING = "RUNNING";
    private static final String ITEM_SUCCEEDED = "SUCCEEDED";
    private static final String ITEM_FAILED = "FAILED";
    private static final String ITEM_SKIPPED = "SKIPPED";
    private static final String ITEM_CANCELLED = "CANCELLED";

    private final NovelAssetService assetService;
    private final AiContentService aiContentService;
    private final AiSceneEndpointResolver endpointResolver;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    /** 每个用户同时只允许一个进行中的连写任务，避免两个任务写同一本书把档案搞乱 */
    private final Map<Long, String> runningByUser = new ConcurrentHashMap<>();

    private final AtomicInteger threadSeq = new AtomicInteger();

    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "chapter-write-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /* ------------------------------------------------------------------ */
    /* 对外接口                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 规划并执行一次连写。
     *
     * <p>端点解析、章节定位都在调用线程完成：AI 没配好或起始章不存在时立刻报错，
     * 不用等任务跑起来才发现。</p>
     */
    public WriteTaskVO start(Long novelId, WriteTaskRequest request, Long userId) {
        purgeExpired();
        WriteTaskRequest safe = request == null ? new WriteTaskRequest() : request;
        int count = safe.getCount() == null ? 1 : Math.max(1, Math.min(MAX_COUNT, safe.getCount()));

        // 端点就绪与否先探一次：配置缺失应该在这个线程就暴露
        endpointResolver.resolve(AiScene.BODY_GEN);

        List<ChapterVO> all = orderedChapters(novelId);
        if (all.isEmpty()) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND, "这本书还没有章节，先建章节再连写");
        }
        int startIndex = locateStart(all, safe);

        UserContext context = UserContextHolder.require();
        String taskId = "wt" + UUID.randomUUID().toString().replace("-", "");
        String existing = runningByUser.putIfAbsent(userId, taskId);
        if (existing != null) {
            throw BizException.of(ErrorCode.WRITE_TASK_RUNNING);
        }

        // 候选队列：起始章之后（含起始章）的全部章节，运行时再逐章决定写 / 跳过
        List<ChapterVO> candidates = new ArrayList<>(all.subList(startIndex, all.size()));
        Task task = new Task(taskId, novelId, userId, count, resolveTargetWords(all, safe), context);
        for (ChapterVO chapter : candidates) {
            task.items.add(new Item(chapter.id().toString(), chapter.chapterNo(), chapter.title()));
        }
        tasks.put(taskId, task);

        try {
            executor.submit(() -> run(task, safe));
        } catch (RejectedExecutionException ex) {
            tasks.remove(taskId);
            runningByUser.remove(userId, taskId);
            throw BizException.of(ErrorCode.AI_CALL_FAILED, "当前生成任务过多，请等前面的写完再试");
        }
        log.info("[write-task] started taskId={} novelId={} userId={} fromNo={} count={} candidates={} targetWords={}",
                taskId, novelId, userId,
                startIndex < all.size() ? all.get(startIndex).chapterNo() : null,
                count, candidates.size(), task.targetWords);
        return toVO(task);
    }

    /** 查询进度；只有任务发起人能查。 */
    public WriteTaskVO status(String taskId, Long userId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        return toVO(task);
    }

    /**
     * 请求停止：置标记后由工作线程在<b>章与章之间</b>退出。
     *
     * <p>正在写的那一章会写完（AI 请求没法安全地半路掐断），没开始的章不会再写。</p>
     */
    public WriteTaskVO cancel(String taskId, Long userId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        task.cancelRequested = true;
        log.info("[write-task] cancel requested taskId={} userId={}", taskId, userId);
        return toVO(task);
    }

    /* ------------------------------------------------------------------ */
    /* 任务执行                                                            */
    /* ------------------------------------------------------------------ */

    private void run(Task task, WriteTaskRequest request) {
        UserContextHolder.set(task.context);
        boolean stopOnError = !Boolean.FALSE.equals(request.getStopOnError());
        boolean skipDone = !Boolean.FALSE.equals(request.getSkipDone());
        try {
            task.stepLabel = "正在准备";
            int written = 0;
            int failedCount = 0;
            int skippedCount = 0;

            for (int i = 0; i < task.items.size() && written < task.total; i++) {
                if (task.cancelRequested) {
                    for (int k = i; k < task.items.size(); k++) {
                        Item rest = task.items.get(k);
                        if (ITEM_PENDING.equals(rest.status)) {
                            rest.status = ITEM_CANCELLED;
                        }
                    }
                    task.finish(STATUS_CANCELLED, written, failedCount, skippedCount,
                            "已停止，已完成 " + written + " 章，尚未开始的章节没有写入");
                    return;
                }

                Item item = task.items.get(i);
                // 每章都重新取：上一章刚写进去的正文与补建的档案，必须能被这一章读到
                ChapterVO current = assetService.getChapter(Long.valueOf(item.chapterId));
                if (skipDone && "DONE".equals(current.status())) {
                    item.status = ITEM_SKIPPED;
                    skippedCount++;
                    continue;
                }

                item.status = ITEM_RUNNING;
                item.title = current.title();
                task.currentChapterNo = item.chapterNo;
                task.currentTitle = item.title;
                task.stepLabel = "正在写第 " + item.chapterNo + " 章";
                task.touch();
                if (current.summary() == null || current.summary().isBlank()) {
                    item.warning = "本章没有大纲摘要，AI 只能按前一章往下接";
                }

                long startedAt = System.currentTimeMillis();
                try {
                    ContentGenerateResultVO result = aiContentService.generateBatch(
                            Long.valueOf(item.chapterId), singleChapterRequest(request, task.targetWords));
                    item.status = ITEM_SUCCEEDED;
                    item.wordCount = result.wordCount();
                    written++;
                    task.writtenWords += result.generatedWords() == null ? 0 : result.generatedWords();
                    log.info("[write-task] chapter done taskId={} no={} words={} latency={}ms",
                            task.id, item.chapterNo, result.wordCount(), System.currentTimeMillis() - startedAt);
                } catch (BizException ex) {
                    failedCount++;
                    item.status = ITEM_FAILED;
                    item.error = ex.getMessage();
                    log.warn("[write-task] chapter failed taskId={} no={} code={} reason={}",
                            task.id, item.chapterNo, ex.getErrorCode(), ex.getMessage());
                    if (stopOnError) {
                        task.finish(STATUS_FAILED, written, failedCount, skippedCount, ex.getMessage());
                        return;
                    }
                } catch (RuntimeException ex) {
                    failedCount++;
                    item.status = ITEM_FAILED;
                    item.error = ex.getMessage() == null || ex.getMessage().isBlank()
                            ? "AI 调用失败" : ex.getMessage();
                    log.warn("[write-task] chapter failed taskId={} no={} reason={}", task.id, item.chapterNo,
                            ex.toString());
                    if (stopOnError) {
                        task.finish(STATUS_FAILED, written, failedCount, skippedCount, item.error);
                        return;
                    }
                }
                task.percent = Math.min(99, written * 100 / task.total);
                task.touch();
            }

            String message = null;
            if (written < task.total) {
                message = "后面没有更多可写的章节了，实际写完 " + written + " 章（计划 " + task.total + " 章）";
            }
            task.finish(failedCount == 0 ? STATUS_SUCCEEDED : STATUS_PARTIAL,
                    written, failedCount, skippedCount, message);
            log.info("[write-task] finished taskId={} status={} written={} failed={} skipped={} words={}",
                    task.id, task.status, written, failedCount, skippedCount, task.writtenWords);
        } catch (RuntimeException ex) {
            task.finish(STATUS_FAILED, task.succeeded, task.failed, task.skipped,
                    ex.getMessage() == null ? "连写失败" : ex.getMessage());
            log.warn("[write-task] unexpected failure taskId={}: {}", task.id, ex.toString());
        } finally {
            runningByUser.remove(task.userId, task.id);
            UserContextHolder.clear();
        }
    }

    /* ------------------------------------------------------------------ */

    private ContentGenerateRequest singleChapterRequest(WriteTaskRequest request, Integer targetWords) {
        ContentGenerateRequest single = new ContentGenerateRequest();
        single.setPrompt(request.getRequirement());
        single.setTargetWords(targetWords);
        // 连写一律覆盖：章节里原有的正文是上一轮留下的旧稿，追加只会让章节越写越长、越写越乱
        single.setMode("REPLACE");
        single.setUseSearch(request.getUseSearch());
        return single;
    }

    private List<ChapterVO> orderedChapters(Long novelId) {
        List<ChapterVO> list = new ArrayList<>(assetService.listChapters(novelId));
        list.sort(Comparator
                .comparing((ChapterVO chapter) -> chapter.volumeNo() == null ? 0 : chapter.volumeNo())
                .thenComparing(chapter -> chapter.chapterNo() == null ? 0 : chapter.chapterNo()));
        return list;
    }

    private int locateStart(List<ChapterVO> all, WriteTaskRequest request) {
        if (request.getFromChapterId() != null && !request.getFromChapterId().isBlank()) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).id() != null && all.get(i).id().toString().equals(request.getFromChapterId().trim())) {
                    return i;
                }
            }
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND, "指定的起始章节不属于这本书");
        }
        if (request.getFromChapterNo() != null) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).chapterNo() != null
                        && all.get(i).chapterNo().intValue() == request.getFromChapterNo()) {
                    return i;
                }
            }
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND, "这本书没有第 " + request.getFromChapterNo() + " 章");
        }
        return 0;
    }

    /** 没指定字数时，取本书最近几章的平均字数，让连写出来的章节长短自然接续。 */
    private int resolveTargetWords(List<ChapterVO> all, WriteTaskRequest request) {
        if (request.getTargetWords() != null && request.getTargetWords() > 0) {
            return Math.min(6000, Math.max(300, request.getTargetWords()));
        }
        List<ChapterVO> written = new ArrayList<>();
        for (ChapterVO chapter : all) {
            if (chapter.wordCount() != null && chapter.wordCount() > 0) {
                written.add(chapter);
            }
        }
        if (written.isEmpty()) {
            return DEFAULT_TARGET_WORDS;
        }
        List<ChapterVO> recent = written.subList(Math.max(0, written.size() - 5), written.size());
        int sum = 0;
        for (ChapterVO chapter : recent) {
            sum += chapter.wordCount();
        }
        int average = sum / recent.size();
        return Math.min(6000, Math.max(300, average));
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Task>> iterator = tasks.entrySet().iterator();
        while (iterator.hasNext()) {
            Task task = iterator.next().getValue();
            if (!STATUS_RUNNING.equals(task.status) && now - task.updatedAt > TASK_TTL_MS) {
                iterator.remove();
            }
        }
    }

    private WriteTaskVO toVO(Task task) {
        List<WriteTaskChapterVO> list = new ArrayList<>(task.items.size());
        for (Item item : task.items) {
            list.add(new WriteTaskChapterVO(item.chapterId, item.chapterNo, item.title,
                    item.status, item.wordCount, item.warning, item.error));
        }
        return new WriteTaskVO(
                task.id,
                task.novelId.toString(),
                task.status,
                task.total,
                task.succeeded,
                task.percent,
                task.currentChapterNo,
                task.currentTitle,
                task.stepLabel,
                task.writtenWords,
                task.succeeded,
                task.failed,
                task.skipped,
                list,
                task.message,
                task.errorMessage,
                task.startedAt,
                task.finishedAt);
    }

    /** 内存任务快照。并发读取（轮询线程）与写入（工作线程）共用，字段一律 volatile。 */
    private static final class Task {

        private final String id;
        private final Long novelId;
        private final Long userId;
        private final UserContext context;
        private final int total;
        private final int targetWords;
        private final long startedAt = System.currentTimeMillis();

        private final List<Item> items = new CopyOnWriteArrayList<>();

        private volatile String status = STATUS_RUNNING;
        private volatile int percent = 0;
        private volatile int succeeded;
        private volatile int failed;
        private volatile int skipped;
        private volatile Integer currentChapterNo;
        private volatile String currentTitle;
        private volatile String stepLabel = "正在准备";
        private volatile String message;
        private volatile String errorMessage;
        private volatile Long finishedAt;
        private volatile long writtenWords;
        private volatile long updatedAt = System.currentTimeMillis();
        private volatile boolean cancelRequested;

        private Task(String id, Long novelId, Long userId, int total, int targetWords, UserContext context) {
            this.id = id;
            this.novelId = novelId;
            this.userId = userId;
            this.total = total;
            this.targetWords = targetWords;
            this.context = context;
        }

        private void touch() {
            this.updatedAt = System.currentTimeMillis();
        }

        private void finish(String status, int written, int failedCount, int skippedCount, String message) {
            this.status = status;
            this.succeeded = written;
            this.failed = failedCount;
            this.skipped = skippedCount;
            this.percent = 100;
            this.currentChapterNo = null;
            this.currentTitle = null;
            this.stepLabel = switch (status) {
                case STATUS_SUCCEEDED -> "连写完成";
                case STATUS_PARTIAL -> "连写完成（有章节失败）";
                case STATUS_CANCELLED -> "已停止";
                default -> "连写失败";
            };
            this.message = message;
            this.finishedAt = System.currentTimeMillis();
            this.updatedAt = this.finishedAt;
            if (STATUS_FAILED.equals(status) || STATUS_PARTIAL.equals(status)) {
                StringBuilder builder = new StringBuilder();
                for (Item item : items) {
                    if (ITEM_FAILED.equals(item.status) && item.error != null) {
                        if (builder.length() > 0) {
                            builder.append("；");
                        }
                        builder.append("第 ").append(item.chapterNo).append(" 章：").append(item.error);
                    }
                }
                this.errorMessage = builder.length() > 0 ? builder.toString() : message;
            }
        }
    }

    private static final class Item {

        private final String chapterId;
        private final Integer chapterNo;
        private volatile String title;
        private volatile String status = ITEM_PENDING;
        private volatile Integer wordCount;
        private volatile String warning;
        private volatile String error;

        private Item(String chapterId, Integer chapterNo, String title) {
            this.chapterId = chapterId;
            this.chapterNo = chapterNo;
            this.title = title;
        }
    }
}
