package com.storyforge.modules.autowrite.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.autowrite.entity.AutoWriteRunEntity;
import com.storyforge.modules.autowrite.entity.AutoWriteTaskEntity;
import com.storyforge.modules.autowrite.mapper.AutoWriteRunMapper;
import com.storyforge.modules.autowrite.mapper.AutoWriteTaskMapper;
import com.storyforge.modules.autowrite.support.AutoWriteGuard;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisRequest;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisVO;
import com.storyforge.modules.bookshelf.dto.ChapterRequest;
import com.storyforge.modules.bookshelf.dto.ChapterReviseRequest;
import com.storyforge.modules.bookshelf.dto.ChapterReviseVO;
import com.storyforge.modules.bookshelf.dto.ConsistencyCheckRequest;
import com.storyforge.modules.bookshelf.dto.ConsistencyIssueVO;
import com.storyforge.modules.bookshelf.dto.ConsistencyTaskVO;
import com.storyforge.modules.bookshelf.dto.ContentGenerateRequest;
import com.storyforge.modules.bookshelf.dto.ContentGenerateResultVO;
import com.storyforge.modules.bookshelf.dto.MasterOutlineGenerateRequest;
import com.storyforge.modules.bookshelf.dto.MasterOutlineVO;
import com.storyforge.modules.bookshelf.dto.OutlineGenerateRequest;
import com.storyforge.modules.bookshelf.dto.OutlineGenerateResultVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterVersionEntity;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.MasterOutlineMapper;
import com.storyforge.modules.bookshelf.service.AiContentService;
import com.storyforge.modules.bookshelf.service.AiOutlineService;
import com.storyforge.modules.bookshelf.service.ChapterAnalysisService;
import com.storyforge.modules.bookshelf.service.ChapterReviseService;
import com.storyforge.modules.bookshelf.service.ConsistencyService;
import com.storyforge.modules.bookshelf.service.MasterOutlineService;
import com.storyforge.modules.bookshelf.service.NovelAssetService;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动写书流水线：一轮把「总纲 → 大纲 → 正文 → 体检 → 收尾」推进完。
 *
 * <p>每轮顺序执行，任一步阻断即结束本轮（已写好的章节保留）：</p>
 * <ol>
 *   <li>补总纲：不足时补到目标条数（单轮最多 {@value #MAX_MASTER_CALLS_PER_RUN} 次 AI 调用）；</li>
 *   <li>补大纲：按总纲顺序找缺口，单次 ≤{@value #OUTLINE_PER_MASTER} 条，不跨总纲，单轮最多补
 *       {@value #MAX_MASTER_GROUPS_PER_RUN} 条总纲；</li>
 *   <li>串行写正文：一章一章来，写完即分析，贴合度 &lt;{@value #OUTLINE_SCORE_THRESHOLD} 重写
 *       （最多 {@value #MAX_BODY_REGEN} 次取最高），AI 率 &lt;{@value #AI_SCORE_THRESHOLD} 自动改写；</li>
 *   <li>每 {@value #HEALTH_INTERVAL} 章体检一次，硬伤（P0）超阈值则熔断停止；</li>
 *   <li>「直到完结」模式做一次收尾评估。</li>
 * </ol>
 *
 * <p>本类在自动写书线程里跑，线程已由调度器 {@code markBypass()} 放行只读锁；同时
 * {@code UserContextHolder} 必须在进入前由调度器写入、结束后清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoWriteRunner {

    /** 每条总纲覆盖的章节数 */
    public static final int OUTLINE_PER_MASTER = 10;
    /** 正文重写次数上限（含首次） */
    public static final int MAX_BODY_REGEN = 3;
    /** AI 调用失败的重试次数（不含首次）；重试用尽即停止本轮，等下个周期 */
    private static final int AI_RETRY_TIMES = 2;
    /** 大纲贴合度阈值：低于它就重写 */
    public static final int OUTLINE_SCORE_THRESHOLD = 90;
    /** AI 率阈值：低于它就改写 */
    public static final int AI_SCORE_THRESHOLD = 80;
    /** 体检间隔（章） */
    public static final int HEALTH_INTERVAL = 10;
    /** 完结缓冲字数：距目标这么多字以内就评估收尾 */
    public static final int MARGIN_WORDS = 10000;
    /** 单轮补总纲最多调用几次 AI */
    public static final int MAX_MASTER_CALLS_PER_RUN = 6;
    /** 单轮最多补几条总纲下的大纲 */
    public static final int MAX_MASTER_GROUPS_PER_RUN = 3;
    /** 报告流水保留轮数 */
    private static final int RUN_KEEP = 50;
    /** 单次写正文的字数上限（受主链路校验约束） */
    private static final int GENERATE_MAX_WORDS = 6000;
    /** 体检 AI 深检轮询上限（毫秒） */
    private static final long HEALTH_POLL_TIMEOUT_MS = 8 * 60 * 1000L;

    private final AutoWriteTaskMapper taskMapper;
    private final AutoWriteRunMapper runMapper;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final MasterOutlineMapper masterOutlineMapper;

    private final MasterOutlineService masterOutlineService;
    private final AiOutlineService aiOutlineService;
    private final AiContentService aiContentService;
    private final ChapterAnalysisService chapterAnalysisService;
    private final ChapterReviseService chapterReviseService;
    private final ConsistencyService consistencyService;
    private final NovelAssetService novelAssetService;
    private final AutoWriteGuard guard;

    /**
     * 跑一轮。
     *
     * @param task  任务快照（调用方已确认可执行）
     * @param context 归属用户上下文（线程内需要）
     * @return 本轮结果
     */
    public RunResult execute(AutoWriteTaskEntity task, UserContext context) {
        UserContextHolder.set(context);
        try {
            return doRun(task);
        } finally {
            UserContextHolder.clear();
        }
    }

    /* ------------------------------------------------------------------ */

    private RunResult doRun(AutoWriteTaskEntity task) {
        Long novelId = task.getNovelId();
        NovelEntity novel = novelMapper.selectById(novelId);
        if (novel == null) {
            return RunResult.failed("小说不存在或已删除");
        }

        int targetWordsPerChapter = clampChapterWords(task.getTargetWordsPerChapter());
        int plannedWords = novel.getPlannedWords() == null ? 0 : novel.getPlannedWords();

        // 目标章节数与总纲目标条数
        int targetChapters;
        if (task.untilEndMode()) {
            if (plannedWords <= 0) {
                return RunResult.failed("尚未设置预计完结字数，无法按「直到完结」推进");
            }
            targetChapters = (int) Math.ceil((double) plannedWords / targetWordsPerChapter);
        } else {
            targetChapters = task.getEndChapterNo() == null ? 0 : task.getEndChapterNo();
            if (targetChapters <= 0) {
                return RunResult.failed("尚未设置目标章数");
            }
        }
        int masterTarget = Math.max(1, (int) Math.ceil((double) targetChapters / OUTLINE_PER_MASTER));

        int masterCreated = 0;
        int outlineCreated = 0;

        // 1) 补总纲
        try {
            masterCreated = fillMasterOutlines(novelId, masterTarget);
        } catch (BizException ex) {
            return RunResult.failed("补总纲失败：" + safeMessage(ex));
        }

        // 2) 补大纲（不跨总纲）
        try {
            outlineCreated = fillChapterOutlines(novelId, task);
        } catch (BizException ex) {
            return RunResult.failed("补大纲失败：" + safeMessage(ex));
        }

        // 3) 串行写正文
        List<ChapterEntity> pending = pendingChapters(novelId);
        int perRun = clampPerRun(task.getChaptersPerRun());
        int written = 0;
        boolean interrupted = false;
        for (ChapterEntity chapter : pending) {
            if (written >= perRun) {
                break;
            }
            try {
                writeOneChapter(chapter, targetWordsPerChapter);
                written++;
            } catch (BizException ex) {
                // 单章失败：本轮到此为止，保留已写章节；下一周期从这一章重来
                log.warn("[auto-write] chapter write failed novelId={} chapterNo={} code={} reason={}",
                        novelId, chapter.getChapterNo(), ex.getErrorCode(), ex.getMessage());
                interrupted = true;
                break;
            }
        }

        // 4) 体检（每满 10 章一次）
        Integer hard = null;
        int completed = completedCount(novelId);
        if (written > 0 && completed > 0 && completed % HEALTH_INTERVAL == 0) {
            try {
                hard = runHealthCheck(novelId, task);
            } catch (RuntimeException ex) {
                log.warn("[auto-write] health check failed novelId={} reason={}", novelId, ex.toString());
            }
            if (hard != null && hard > intOr(task.getHardIssueLimit(), 5)) {
                int limit = intOr(task.getHardIssueLimit(), 5);
                String reason = "体检硬伤 " + hard + " 条，超过阈值 " + limit + "，已停下等待人工介入";
                markPaused(task, reason, hard);
                writeRun(task, AutoWriteRunEntity.STATUS_FAILED, written, outlineCreated, masterCreated, hard, reason);
                return RunResult.stopped(reason, written, outlineCreated, masterCreated, hard);
            }
        }

        // 5) 收尾评估（只在「直到完结」模式）
        if (task.untilEndMode() && !task.closingTriggeredOn()) {
            try {
                int extra = closingEvaluation(novel, task, targetChapters, targetWordsPerChapter);
                outlineCreated += extra;
            } catch (RuntimeException ex) {
                log.warn("[auto-write] closing evaluation failed novelId={} reason={}", novelId, ex.toString());
            }
        }

        // 6) 终止判定
        completed = completedCount(novelId);
        int totalWords = currentWordCount(novel);
        String message;
        if (!task.untilEndMode() && completed >= targetChapters) {
            markCompleted(task, "已完成目标 " + targetChapters + " 章");
            message = "已完成：共 " + completed + " 章";
            writeRun(task, AutoWriteRunEntity.STATUS_SUCCEEDED, written, outlineCreated, masterCreated, hard, message);
            return RunResult.completed(message, written, outlineCreated, masterCreated, hard);
        }
        if (task.untilEndMode() && plannedWords > 0 && completed >= targetChapters && totalWords >= plannedWords) {
            markCompleted(task, "已达到预计完结字数 " + plannedWords);
            message = "已完成：共 " + completed + " 章 / " + totalWords + " 字";
            writeRun(task, AutoWriteRunEntity.STATUS_SUCCEEDED, written, outlineCreated, masterCreated, hard, message);
            return RunResult.completed(message, written, outlineCreated, masterCreated, hard);
        }

        message = "本轮生成 " + written + " 章"
                + (masterCreated > 0 ? "，补总纲 " + masterCreated + " 条" : "")
                + (outlineCreated > 0 ? "，补大纲 " + outlineCreated + " 条" : "")
                + (hard != null ? "，体检硬伤 " + hard + " 条" : "");
        if (interrupted) {
            // 中断也算失败：连续失败计数会累加，达标后熔断
            writeRun(task, AutoWriteRunEntity.STATUS_FAILED, written, outlineCreated, masterCreated, hard,
                    "中途失败，已保留本轮写好的 " + written + " 章");
            return RunResult.failed("本轮中途失败，已保留 " + written + " 章");
        }
        writeRun(task, AutoWriteRunEntity.STATUS_SUCCEEDED, written, outlineCreated, masterCreated, hard, message);
        return RunResult.succeeded(message, written, outlineCreated, masterCreated, hard);
    }

    /* ------------------------------------------------------------------ */
    /* 1) 补总纲                                                           */
    /* ------------------------------------------------------------------ */

    private int fillMasterOutlines(Long novelId, int masterTarget) {
        int current = countMasterOutlines(novelId);
        if (current >= masterTarget) {
            return 0;
        }
        int created = 0;
        int calls = 0;
        while (current + created < masterTarget && calls < MAX_MASTER_CALLS_PER_RUN) {
            int need = Math.min(12, masterTarget - (current + created));
            MasterOutlineGenerateRequest request = new MasterOutlineGenerateRequest();
            request.setCount(need);
            request.setPrompt("请按全书规模一次性规划到位，覆盖从开局到完结的完整走向。");
            List<MasterOutlineVO> result = masterOutlineService.generate(novelId, request);
            calls++;
            if (result == null || result.isEmpty()) {
                break;
            }
            created += result.size();
        }
        if (created > 0) {
            log.info("[auto-write] master outlines created novelId={} count={} calls={}", novelId, created, calls);
        }
        return created;
    }

    /* ------------------------------------------------------------------ */
    /* 2) 补大纲                                                           */
    /* ------------------------------------------------------------------ */

    private int fillChapterOutlines(Long novelId, AutoWriteTaskEntity task) {
        int created = 0;
        int groups = 0;
        while (groups < MAX_MASTER_GROUPS_PER_RUN) {
            List<MasterOutlineEntity> masters = masterOutlines(novelId);
            MasterOutlineEntity gapMaster = null;
            for (MasterOutlineEntity master : masters) {
                if (countChaptersUnderMaster(novelId, master.getId()) < OUTLINE_PER_MASTER) {
                    gapMaster = master;
                    break;
                }
            }
            if (gapMaster == null) {
                break;
            }
            int have = countChaptersUnderMaster(novelId, gapMaster.getId());
            int need = Math.min(OUTLINE_PER_MASTER - have, OUTLINE_PER_MASTER);
            if (need <= 0) {
                break;
            }
            OutlineGenerateRequest request = new OutlineGenerateRequest();
            request.setMasterOutlineId(gapMaster.getId());
            request.setChapterCount(need);
            request.setStartChapterNo(nextChapterNo(novelId));
            request.setUseSearch(Boolean.FALSE);
            OutlineGenerateResultVO result = aiOutlineService.generate(novelId, request);
            groups++;
            int made = result == null || result.chapters() == null ? 0 : result.chapters().size();
            if (made == 0) {
                break;
            }
            created += made;
        }
        if (created > 0) {
            log.info("[auto-write] chapter outlines created novelId={} count={}", novelId, created);
        }
        return created;
    }

    /* ------------------------------------------------------------------ */
    /* 3) 写正文                                                           */
    /* ------------------------------------------------------------------ */

    private void writeOneChapter(ChapterEntity chapter, int targetWords) {
        Long chapterId = chapter.getId();

        // 3.1 生成正文 + 分析：最多 MAX_BODY_REGEN 次，记录最高分那版
        String bestContent = null;
        int bestOutline = -1;
        int bestAi = -1;
        int lastAi = -1;
        String lastContent = null;
        for (int attempt = 1; attempt <= MAX_BODY_REGEN; attempt++) {
            ContentGenerateResultVO generated = generateBodyWithRetry(chapterId, targetWords);
            String content = generated == null ? null : generated.content();
            int[] scores = analyzeScores(chapterId, targetWords);
            int outline = scores[0];
            int ai = scores[1];
            lastContent = content;
            lastAi = ai;
            if (outline > bestOutline) {
                bestOutline = outline;
                bestContent = content;
                bestAi = ai;
            }
            if (outline >= OUTLINE_SCORE_THRESHOLD) {
                break;
            }
        }

        // 最后一版不是最高分 → 写回最高分那版
        String finalContent = lastContent;
        int finalAi = lastAi;
        if (bestContent != null && !bestContent.equals(lastContent)) {
            finalContent = bestContent;
            finalAi = bestAi;
        }

        // 3.2 AI 率不足 → 改写一次
        if (finalAi >= 0 && finalAi < AI_SCORE_THRESHOLD) {
            try {
                ChapterReviseRequest revise = new ChapterReviseRequest();
                revise.setIntensity("MEDIUM");
                revise.setInstruction("本章 AI 痕迹偏重，请改得更像真人写作：口语化一点、长短句错落、"
                        + "少用排比与总结句，保留原有剧情与信息。");
                revise.setTargetWords(targetWords);
                ChapterReviseVO revised = chapterReviseService.revise(chapterId, revise);
                if (revised != null && revised.revisedContent() != null
                        && !revised.revisedContent().isBlank()) {
                    finalContent = revised.revisedContent();
                }
            } catch (BizException ex) {
                log.warn("[auto-write] revise failed chapterId={} reason={}", chapterId, ex.getMessage());
            }
        }

        // 3.3 落地最终稿并置为已完成
        ChapterRequest save = new ChapterRequest();
        if (finalContent != null) {
            save.setContent(finalContent);
        }
        save.setStatus("DONE");
        novelAssetService.updateChapter(chapterId, save, ChapterVersionEntity.SOURCE_AUTO_WRITE);
    }

    /** 生成正文：失败重试 {@value #AI_RETRY_TIMES} 次，仍失败则抛出（本轮到此为止）。 */
    private ContentGenerateResultVO generateBodyWithRetry(Long chapterId, int targetWords) {
        BizException last = null;
        for (int i = 0; i <= AI_RETRY_TIMES; i++) {
            try {
                ContentGenerateRequest request = new ContentGenerateRequest();
                request.setMode("REPLACE");
                request.setTargetWords(targetWords);
                request.setUseSearch(Boolean.FALSE);
                return aiContentService.generate(chapterId, request);
            } catch (BizException ex) {
                last = ex;
                log.warn("[auto-write] body generate failed attempt={}/{} chapterId={} code={} reason={}",
                        i + 1, AI_RETRY_TIMES + 1, chapterId, ex.getErrorCode(), ex.getMessage());
                if (i < AI_RETRY_TIMES) {
                    sleep(1500L * (i + 1));
                }
            }
        }
        throw last;
    }

    /** 分析并取 [outlineScore, aiScore]；分析前置不满足（顺序问题）时按「达标」处理，避免死循环。 */
    private int[] analyzeScores(Long chapterId, int targetWords) {
        BizException last = null;
        for (int i = 0; i <= AI_RETRY_TIMES; i++) {
            try {
                ChapterAnalysisRequest request = new ChapterAnalysisRequest();
                request.setTargetWords(targetWords);
                ChapterAnalysisVO vo = chapterAnalysisService.analyze(chapterId, request);
                int outline = vo == null || vo.outlineScore() == null ? -1 : vo.outlineScore();
                int ai = vo == null || vo.aiScore() == null ? -1 : vo.aiScore();
                return new int[]{outline, ai};
            } catch (BizException ex) {
                if (ex.getErrorCode() == ErrorCode.CHAPTER_ORDER_VIOLATION) {
                    log.warn("[auto-write] analyze skipped (order) chapterId={}", chapterId);
                    return new int[]{OUTLINE_SCORE_THRESHOLD, AI_SCORE_THRESHOLD};
                }
                last = ex;
                log.warn("[auto-write] analyze failed attempt={}/{} chapterId={} code={} reason={}",
                        i + 1, AI_RETRY_TIMES + 1, chapterId, ex.getErrorCode(), ex.getMessage());
                if (i < AI_RETRY_TIMES) {
                    sleep(1500L * (i + 1));
                }
            }
        }
        throw last;
    }

    /* ------------------------------------------------------------------ */
    /* 4) 体检                                                             */
    /* ------------------------------------------------------------------ */

    /** 跑一次体检，返回硬伤（P0）条数。 */
    private Integer runHealthCheck(Long novelId, AutoWriteTaskEntity task) {
        if (task.consistencyAiOn()) {
            // 规则 + AI 深检（最近 10 章），走异步任务并轮询到结束
            try {
                ConsistencyCheckRequest request = new ConsistencyCheckRequest();
                request.setScope(ConsistencyCheckRequest.SCOPE_RECENT);
                request.setRecentCount(10);
                ConsistencyTaskVO started = consistencyService.start(novelId,
                        request, UserContextHolder.requireUserId());
                long deadline = System.currentTimeMillis() + HEALTH_POLL_TIMEOUT_MS;
                while (System.currentTimeMillis() < deadline) {
                    ConsistencyTaskVO status = consistencyService.status(started.taskId(),
                            UserContextHolder.requireUserId());
                    if (isTerminal(status.status())) {
                        return status.p0();
                    }
                    sleep(2000L);
                }
                log.warn("[auto-write] health ai check timeout novelId={}", novelId);
            } catch (BizException ex) {
                if (ex.getErrorCode() != ErrorCode.CONSISTENCY_TASK_RUNNING) {
                    throw ex;
                }
                log.warn("[auto-write] health ai check skipped (another running) novelId={}", novelId);
            }
        }
        // 规则层：零 token，秒级
        List<ConsistencyIssueVO> issues = consistencyService.checkRules(novelId);
        int p0 = 0;
        if (issues != null) {
            for (ConsistencyIssueVO issue : issues) {
                if ("P0".equalsIgnoreCase(issue.severity())) {
                    p0++;
                }
            }
        }
        return p0;
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "PARTIAL".equals(status)
                || "FAILED".equals(status) || "CANCELLED".equals(status);
    }

    /* ------------------------------------------------------------------ */
    /* 5) 收尾评估                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * 「直到完结」模式的收尾评估，返回本次因收尾新建的大纲条数。
     *
     * <p>触发一次后把 {@code closing_triggered} 置 1，之后不再干预。三种情形见设计文档。</p>
     */
    private int closingEvaluation(NovelEntity novel, AutoWriteTaskEntity task,
                                  int targetChapters, int targetWordsPerChapter) {
        Long novelId = novel.getId();
        int plannedWords = novel.getPlannedWords() == null ? 0 : novel.getPlannedWords();
        int totalWords = currentWordCount(novel);
        if (totalWords < plannedWords - MARGIN_WORDS) {
            return 0;
        }
        // 触发收尾评估，置标记（只触发一次）
        markClosingTriggered(task);

        int planned = countChapters(novelId);
        int completed = completedCount(novelId);
        int remaining = Math.max(0, planned - completed);
        long estimated = (long) remaining * targetWordsPerChapter;

        if (estimated <= MARGIN_WORDS) {
            log.info("[auto-write] closing A: remaining small novelId={} remaining={}", novelId, remaining);
            return 0;
        }
        if (remaining <= OUTLINE_PER_MASTER) {
            log.info("[auto-write] closing B: generate ending arc novelId={} remaining={}", novelId, remaining);
            MasterOutlineGenerateRequest request = new MasterOutlineGenerateRequest();
            request.setCount(1);
            request.setPrompt("这是全书的收尾：请生成一条「结局向」的总纲，明确把主线冲突收束、"
                    + "交代主要人物归宿，后续章节将在 10 章内完结。");
            List<MasterOutlineVO> created = masterOutlineService.generate(novelId, request);
            return created == null ? 0 : created.size();
        }
        log.info("[auto-write] closing C: skip, keep creating novelId={} remaining={}", novelId, remaining);
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /* 状态与流水                                                          */
    /* ------------------------------------------------------------------ */

    private void markCompleted(AutoWriteTaskEntity task, String reason) {
        AutoWriteTaskEntity patch = new AutoWriteTaskEntity();
        patch.setId(task.getId());
        patch.setStatus(AutoWriteTaskEntity.STATUS_COMPLETED);
        patch.setStoppedReason(reason);
        patch.setLastRunAt(LocalDateTime.now());
        patch.setLastResult(reason);
        taskMapper.updateById(patch);
    }

    private void markPaused(AutoWriteTaskEntity task, String reason, Integer hard) {
        AutoWriteTaskEntity patch = new AutoWriteTaskEntity();
        patch.setId(task.getId());
        patch.setStatus(AutoWriteTaskEntity.STATUS_PAUSED);
        patch.setStoppedReason(reason);
        patch.setLastRunAt(LocalDateTime.now());
        patch.setLastResult(reason);
        taskMapper.updateById(patch);
    }

    private void markClosingTriggered(AutoWriteTaskEntity task) {
        AutoWriteTaskEntity patch = new AutoWriteTaskEntity();
        patch.setId(task.getId());
        patch.setClosingTriggered(1);
        taskMapper.updateById(patch);
        task.setClosingTriggered(1);
    }

    /** 写一条执行流水，并把超出保留上限的旧流水逻辑删掉。 */
    private void writeRun(AutoWriteTaskEntity task, String status, int chapters, int outlines,
                          int masters, Integer hard, String message) {
        AutoWriteRunEntity run = new AutoWriteRunEntity();
        run.setUserId(task.getUserId());
        run.setNovelId(task.getNovelId());
        run.setTaskId(task.getId());
        run.setStartedAt(LocalDateTime.now());
        run.setFinishedAt(LocalDateTime.now());
        run.setStatus(status);
        run.setChaptersWritten(chapters);
        run.setOutlinesCreated(outlines);
        run.setMasterOutlinesCreated(masters);
        run.setConsistencyHard(hard);
        run.setMessage(trim(message, 500));
        run.setDeleted(0);
        run.setVersion(0);
        try {
            runMapper.insert(run);
            pruneRuns(task.getId());
        } catch (RuntimeException ex) {
            log.warn("[auto-write] write run failed taskId={} reason={}", task.getId(), ex.toString());
        }
    }

    private void pruneRuns(Long taskId) {
        List<AutoWriteRunEntity> runs = runMapper.selectList(Wrappers.<AutoWriteRunEntity>lambdaQuery()
                .eq(AutoWriteRunEntity::getTaskId, taskId)
                .orderByDesc(AutoWriteRunEntity::getStartedAt));
        if (runs.size() <= RUN_KEEP) {
            return;
        }
        for (int i = RUN_KEEP; i < runs.size(); i++) {
            runMapper.deleteById(runs.get(i).getId());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 计数与查询                                                          */
    /* ------------------------------------------------------------------ */

    private int countMasterOutlines(Long novelId) {
        Long count = masterOutlineMapper.selectCount(Wrappers.<MasterOutlineEntity>lambdaQuery()
                .eq(MasterOutlineEntity::getNovelId, novelId));
        return count == null ? 0 : count.intValue();
    }

    private List<MasterOutlineEntity> masterOutlines(Long novelId) {
        return masterOutlineMapper.selectList(Wrappers.<MasterOutlineEntity>lambdaQuery()
                .eq(MasterOutlineEntity::getNovelId, novelId)
                .orderByAsc(MasterOutlineEntity::getSortNo));
    }

    private int countChaptersUnderMaster(Long novelId, Long masterOutlineId) {
        Long count = chapterMapper.selectCount(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .eq(ChapterEntity::getMasterOutlineId, masterOutlineId));
        return count == null ? 0 : count.intValue();
    }

    private int nextChapterNo(Long novelId) {
        List<ChapterEntity> last = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .select(ChapterEntity::getChapterNo)
                .eq(ChapterEntity::getNovelId, novelId)
                .orderByDesc(ChapterEntity::getChapterNo)
                .last("limit 1"));
        if (last.isEmpty() || last.get(0).getChapterNo() == null) {
            return 1;
        }
        return last.get(0).getChapterNo() + 1;
    }

    private int countChapters(Long novelId) {
        Long count = chapterMapper.selectCount(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId));
        return count == null ? 0 : count.intValue();
    }

    private int completedCount(Long novelId) {
        Long count = chapterMapper.selectCount(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .gt(ChapterEntity::getWordCount, 0));
        return count == null ? 0 : count.intValue();
    }

    /** 已规划、未写正文的章节，按章号升序。 */
    private List<ChapterEntity> pendingChapters(Long novelId) {
        return chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .and(w -> w.isNull(ChapterEntity::getWordCount).or().le(ChapterEntity::getWordCount, 0))
                .orderByAsc(ChapterEntity::getChapterNo));
    }

    private int currentWordCount(NovelEntity novel) {
        if (novel.getWordCount() != null) {
            return novel.getWordCount();
        }
        Long sum = 0L;
        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .select(ChapterEntity::getWordCount)
                .eq(ChapterEntity::getNovelId, novel.getId()));
        for (ChapterEntity chapter : chapters) {
            sum += chapter.getWordCount() == null ? 0 : chapter.getWordCount();
        }
        return (int) Math.min(sum, Integer.MAX_VALUE);
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

    private int clampChapterWords(Integer value) {
        int v = value == null ? 3000 : value;
        return Math.max(300, Math.min(GENERATE_MAX_WORDS, v));
    }

    private int clampPerRun(Integer value) {
        int v = value == null ? 3 : value;
        return Math.max(1, Math.min(20, v));
    }

    private int intOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private String safeMessage(BizException ex) {
        return ex.getMessage() == null || ex.getMessage().isBlank() ? ex.getErrorCode().getMessage() : ex.getMessage();
    }

    private String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一轮的结果。
     *
     * @param status    SUCCEEDED / FAILED / STOPPED / COMPLETED
     */
    public record RunResult(String status, String message, int chaptersWritten,
                            int outlinesCreated, int masterOutlinesCreated, Integer consistencyHard) {

        static RunResult succeeded(String message, int chapters, int outlines, int masters, Integer hard) {
            return new RunResult("SUCCEEDED", message, chapters, outlines, masters, hard);
        }

        static RunResult failed(String message) {
            return new RunResult("FAILED", message, 0, 0, 0, null);
        }

        static RunResult failed(String message, int chapters, int outlines, int masters, Integer hard) {
            return new RunResult("FAILED", message, chapters, outlines, masters, hard);
        }

        static RunResult stopped(String message, int chapters, int outlines, int masters, Integer hard) {
            return new RunResult("STOPPED", message, chapters, outlines, masters, hard);
        }

        static RunResult completed(String message, int chapters, int outlines, int masters, Integer hard) {
            return new RunResult("COMPLETED", message, chapters, outlines, masters, hard);
        }

        public boolean ok() {
            return "SUCCEEDED".equals(status);
        }
    }
}
