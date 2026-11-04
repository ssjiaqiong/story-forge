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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动写书流水线：一轮把「总纲 → 大纲 → 正文 → 体检 → 收尾」推进完。
 *
 * <p>每轮顺序执行，任一步阻断即结束本轮（已写好的章节保留）：</p>
 * <ol>
 *   <li>补总纲：按目标章数粗估需要几条，不足就补（单轮最多 {@value #MAX_MASTER_CALLS_PER_RUN} 次 AI 调用）；</li>
 *   <li>推进当前总纲：只有「当前这一段」在排大纲，一条总纲下的故事讲完（模型判定）才关掉它、
 *       才轮到下一条；每次生成 {@value #OUTLINE_BATCH} 条，单轮最多调用
 *       {@value #MAX_OUTLINE_CALLS_PER_RUN} 次；</li>
 *   <li>串行写正文：一章一章来，写完即分析，贴合度 &lt;{@value #OUTLINE_SCORE_THRESHOLD} 重写
 *       （最多 {@value #MAX_BODY_REGEN} 次取最高，每次把缺失项喂回提示词），AI 率
 *       &lt;{@value #AI_SCORE_THRESHOLD} 自动改写；</li>
 *   <li>每 {@value #HEALTH_INTERVAL} 章体检一次，硬伤（P0）超阈值则熔断停止；</li>
 *   <li>「直到完结」模式做一次收尾评估。</li>
 * </ol>
 *
 * <p><b>总纲不再是「固定 10 章一段」</b>：一条总纲该拆多少条章节大纲，由故事本身决定——
 * 模型在生成大纲时一并判断「这一段讲完没有」，讲完才把总纲置为
 * {@link MasterOutlineEntity#STATUS_DONE}。总纲上的 {@code plannedOutlines} 只是参考值，
 * 相差很多也不会被强行截断。</p>
 *
 * <p>本类在自动写书线程里跑，线程已由调度器 {@code markBypass()} 放行只读锁；同时
 * {@code UserContextHolder} 必须在进入前由调度器写入、结束后清理。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AutoWriteRunner {

    /**
     * 归总纲数量估算用的「一条总纲大概覆盖几章」。
     *
     * <p>只用于回答「还要不要补总纲」，不参与「这条总纲讲完没有」的判断——后者由模型按内容判定。</p>
     */
    private static final int CHAPTERS_PER_MASTER_ESTIMATE = 15;
    /** 总纲没写预计大纲数时的参考值 */
    public static final int DEFAULT_PLANNED_OUTLINES = 12;
    /** 为一条总纲单次生成的章节大纲条数 */
    private static final int OUTLINE_BATCH = 6;
    /** 一条总纲下大纲条数的硬上限：防模型永远说「没讲完」导致无限补 */
    private static final int MAX_OUTLINES_PER_MASTER = 40;
    /** 正文重写次数上限（含首次）：默认 2。同分布重采样第 3 次几乎无收益，省下的 token 拿去抽事实更值 */
    public static final int MAX_BODY_REGEN = 2;
    /** 定向修补的段落保留率下限：低于它就认为模型把整章推倒重写了，退回整章重生成一次 */
    private static final double MIN_PATCH_KEEP_RATIO = 0.30D;
    /** AI 调用失败的重试次数（不含首次）；重试用尽即停止本轮，等下个周期 */
    private static final int AI_RETRY_TIMES = 2;
    /** 大纲贴合度参考线：低于它才考虑修补；真正的通过判定见 {@link #passOutline} */
    public static final int OUTLINE_SCORE_THRESHOLD = 80;
    /** AI 率阈值：低于它就改写 */
    public static final int AI_SCORE_THRESHOLD = 80;
    /** 体检间隔（章）：默认 5，早发现早回退，代价是多一次 AI 调用 */
    public static final int HEALTH_INTERVAL = 5;
    /** 完结缓冲字数：距目标这么多字以内就评估收尾 */
    public static final int MARGIN_WORDS = 10000;
    /** 单轮补总纲最多调用几次 AI */
    public static final int MAX_MASTER_CALLS_PER_RUN = 4;
    /** 总纲全部讲完但章节还不够时，一轮往后接着规划几条 */
    private static final int CONTINUE_MASTER_COUNT = 3;
    /** 单轮为总纲补大纲最多调用几次 AI */
    public static final int MAX_OUTLINE_CALLS_PER_RUN = 4;
    /** 报告流水保留轮数 */
    private static final int RUN_KEEP = 50;
    /** 单次写正文的字数上限（受主链路校验约束） */
    private static final int GENERATE_MAX_WORDS = 6000;
    /** 体检 AI 深检轮询上限（毫秒） */
    private static final long HEALTH_POLL_TIMEOUT_MS = 8 * 60 * 1000L;

    /** 正文重写次数上限（可配；默认取 {@link #MAX_BODY_REGEN}） */
    @org.springframework.beans.factory.annotation.Value("${storyforge.autowrite.max-body-regen:2}")
    private int maxBodyRegen = MAX_BODY_REGEN;
    /** 大纲贴合度参考线（可配；默认取 {@link #OUTLINE_SCORE_THRESHOLD}） */
    @org.springframework.beans.factory.annotation.Value("${storyforge.autowrite.outline-score-threshold:80}")
    private int outlineScoreThreshold = OUTLINE_SCORE_THRESHOLD;
    /** 体检间隔章数（可配；默认取 {@link #HEALTH_INTERVAL}） */
    @org.springframework.beans.factory.annotation.Value("${storyforge.autowrite.health-interval:5}")
    private int healthInterval = HEALTH_INTERVAL;

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
        int masterTarget = estimateMasterTarget(targetChapters);

        int masterCreated = 0;
        OutlineAdvance advance = OutlineAdvance.empty();

        // 1) 补总纲
        try {
            masterCreated = fillMasterOutlines(novelId, masterTarget, targetChapters);
        } catch (BizException ex) {
            return RunResult.failed("补总纲失败：" + safeMessage(ex));
        }

        // 2) 推进当前总纲：只有这一段在排大纲，讲完才关、关了才开下一条
        try {
            advance = advanceCurrentMaster(novelId);
        } catch (BizException ex) {
            if (ex.getErrorCode() == ErrorCode.OUTLINE_GENERATE_RUNNING) {
                // 用户正在手动排大纲：本轮不排，接着写已有的章节，下个周期再补
                log.info("[auto-write] outline busy, skip planning this round novelId={}", novelId);
            } else {
                return RunResult.failed("补大纲失败：" + safeMessage(ex));
            }
        }
        int outlineCreated = advance.created();

        // 3) 串行写正文
        List<ChapterEntity> pending = writableChapters(novelId);
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
        if (written > 0 && completed > 0 && completed % healthInterval == 0) {
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
                + (advance.closedTitle() != null ? "，总纲「" + advance.closedTitle() + "」已讲完" : "")
                + (advance.currentTitle() != null ? "，当前总纲「" + advance.currentTitle() + "」" : "")
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

    /**
     * 补总纲。
     *
     * <p>两个触发条件：① 条数还不够 {@code masterTarget}；② 条数够了，但**所有总纲都已讲完**
     * 而规划出的章节数还不够目标章数——说明前面几条总纲体量偏小，得继续往后规划。
     * 少了 ② 会出现「总纲全部 DONE、没有章节可写、每轮空转」的死局。</p>
     */
    private int fillMasterOutlines(Long novelId, int masterTarget, int targetChapters) {
        int current = countMasterOutlines(novelId);
        int missing = masterTarget - current;
        if (missing <= 0) {
            boolean exhausted = current > 0 && currentMaster(novelId) == null;
            boolean shortOfChapters = countChapters(novelId) < targetChapters;
            if (!exhausted || !shortOfChapters) {
                return 0;
            }
            // 全部讲完但章节不够：继续往后规划，一次补一小段，避免一轮里堆出几十条
            missing = CONTINUE_MASTER_COUNT;
            log.info("[auto-write] all masters closed but chapters short novelId={} planned={} target={}",
                    novelId, countChapters(novelId), targetChapters);
        }
        // 每条总纲至少覆盖 1 章，总纲数不该超过目标章数
        int room = Math.max(0, targetChapters - current);
        missing = Math.min(missing, room);
        if (missing <= 0) {
            return 0;
        }
        int created = 0;
        int calls = 0;
        while (created < missing && calls < MAX_MASTER_CALLS_PER_RUN) {
            int need = Math.min(12, missing - created);
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
    /* 2) 推进当前总纲的大纲                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 按顺序推进「当前这一段」：为它补大纲，讲完了才关掉，关掉下一次才轮到下一条。
     *
     * <p>关键规则（与老的「固定 10 章一段」彻底不同）：</p>
     * <ul>
     *   <li>同一时刻只有一条总纲在排大纲：{@code status} 不是 DONE 的第一条；</li>
     *   <li>一条总纲排多少条大纲由内容决定，每次 {@value #OUTLINE_BATCH} 条，直到模型判定
     *       「这一段讲完」（{@code masterFinished}）才置 DONE；</li>
     *   <li>{@code plannedOutlines} 只是参考：模型没给判断时才退化成「到了参考值就算讲完」；</li>
     *   <li>硬上限 {@value #MAX_OUTLINES_PER_MASTER} 条，防止模型永远说没讲完。</li>
     * </ul>
     */
    private OutlineAdvance advanceCurrentMaster(Long novelId) {
        int created = 0;
        String closedTitle = null;
        int calls = 0;
        MasterOutlineEntity current = null;

        while (calls < MAX_OUTLINE_CALLS_PER_RUN) {
            current = currentMaster(novelId);
            if (current == null) {
                break;
            }
            Long masterId = current.getId();
            if (!MasterOutlineEntity.STATUS_RUNNING.equals(current.getStatus())) {
                masterOutlineService.markStatus(masterId, MasterOutlineEntity.STATUS_RUNNING);
                current.setStatus(MasterOutlineEntity.STATUS_RUNNING);
            }
            int have = countChaptersUnderMaster(novelId, masterId);
            if (have >= MAX_OUTLINES_PER_MASTER) {
                closeMaster(current, "大纲已达硬上限 " + MAX_OUTLINES_PER_MASTER + " 条");
                closedTitle = current.getTitle();
                log.info("[auto-write] master forced closed novelId={} masterId={} outlines={}",
                        novelId, masterId, have);
                continue;
            }

            int need = Math.min(OUTLINE_BATCH, MAX_OUTLINES_PER_MASTER - have);
            OutlineGenerateRequest request = new OutlineGenerateRequest();
            request.setMasterOutlineId(masterId);
            request.setChapterCount(need);
            request.setStartChapterNo(nextChapterNo(novelId));
            request.setUseSearch(Boolean.FALSE);
            request.setPrompt("接着这条总纲已有的章节往下写，把这一段的故事讲完；"
                    + "已经排过的情节不要再写一遍。");
            OutlineGenerateResultVO result = aiOutlineService.generate(novelId, request);
            calls++;
            int made = result == null || result.chapters() == null ? 0 : result.chapters().size();
            if (made == 0) {
                // 生不出来就停，别在本轮里死循环；下个周期再来
                break;
            }
            created += made;

            int total = have + made;
            boolean finished = masterFinished(current, total, result);
            log.info("[auto-write] master progress novelId={} masterId={} planned={} now={} finished={} reason={}",
                    novelId, masterId, referenceOutlines(current), total, finished,
                    result == null ? null : result.masterReason());
            if (finished) {
                closeMaster(current, result == null ? null : result.masterReason());
                closedTitle = current.getTitle();
                // 关掉之后继续下一轮循环：下一条总纲可以在同一轮里开个头
            }
        }

        if (created > 0) {
            log.info("[auto-write] chapter outlines created novelId={} count={} calls={} closed={}",
                    novelId, created, calls, closedTitle);
        }
        return new OutlineAdvance(created, closedTitle,
                current == null ? null : current.getTitle());
    }

    /**
     * 这一段讲完没有？
     *
     * <p>以模型判断为准；模型没给（返回 null）时退回参考值：到了 estimated 条就算讲完。
     * 注意返回 false 不等于「再硬塞几条」，下一批会接着往后写。</p>
     */
    private boolean masterFinished(MasterOutlineEntity master, int total, OutlineGenerateResultVO result) {
        Boolean judged = result == null ? null : result.masterFinished();
        if (judged != null) {
            return judged;
        }
        int reference = referenceOutlines(master);
        return total >= reference;
    }

    /** 这条总纲的参考大纲数：优先取模型规划时给的值，没给就用默认值。 */
    private int referenceOutlines(MasterOutlineEntity master) {
        Integer planned = master.getPlannedOutlines();
        return planned == null || planned <= 0 ? DEFAULT_PLANNED_OUTLINES : planned;
    }

    /** 把总纲置为已完成。 */
    private void closeMaster(MasterOutlineEntity master, String reason) {
        masterOutlineService.markStatus(master.getId(), MasterOutlineEntity.STATUS_DONE);
        log.info("[auto-write] master closed masterId={} title={} reason={}",
                master.getId(), master.getTitle(), reason);
    }

    /** 当前该推进的总纲：{@code sort_no} 升序里第一条不是 DONE 的。 */
    private MasterOutlineEntity currentMaster(Long novelId) {
        for (MasterOutlineEntity master : masterOutlineService.ordered(novelId)) {
            String status = master.getStatus();
            if (!MasterOutlineEntity.STATUS_DONE.equals(status)) {
                return master;
            }
        }
        return null;
    }

    /** 按目标章数粗估需要几条总纲（只用于决定「还要不要补」）。 */
    private int estimateMasterTarget(int targetChapters) {
        return Math.max(1, (int) Math.ceil((double) targetChapters / CHAPTERS_PER_MASTER_ESTIMATE));
    }

    /** 一轮里总纲推进的结果。 */
    private record OutlineAdvance(int created, String closedTitle, String currentTitle) {

        static OutlineAdvance empty() {
            return new OutlineAdvance(0, null, null);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3) 写正文                                                           */
    /* ------------------------------------------------------------------ */

    private void writeOneChapter(ChapterEntity chapter, int targetWords) {
        Long chapterId = chapter.getId();
        int chapterNo = chapter.getChapterNo() == null ? 0 : chapter.getChapterNo();

        // 3.1 生成正文 + 分析：最多 MAX_BODY_REGEN 次，记录最高分那版。
        //     第 2 次起把上一版的「漏掉的剧情点 / 修改建议」喂回提示词——
        //     提示词一字不改地重发，模型只会换个说法重写一遍，分数基本不动。
        String bestContent = null;
        int bestOutline = -1;
        int bestAi = -1;
        String lastContent = null;
        int lastAi = -1;
        boolean analysisUnavailable = false;
        List<String> misses = List.of();
        List<String> suggestions = List.of();

        for (int attempt = 1; attempt <= maxBodyRegen; attempt++) {
            String content;
            if (attempt == 1) {
                ContentGenerateResultVO generated = generateBodyWithRetry(chapterId, targetWords, null);
                content = generated == null ? null : generated.content();
            } else {
                // 从第 2 次起不再整章重采样，而是在上一版上「定向修补」：
                // 同模型同上下文重生成，期望值不变，上一版的错这一版大概率还犯。
                content = patchBody(chapterId, targetWords, lastContent, misses, suggestions);
            }
            ScoreResult score = analyzeScores(chapterId, targetWords);

            lastContent = content;
            lastAi = score.ai();
            misses = score.misses();
            suggestions = score.suggestions();
            analysisUnavailable = score.unavailable();
            if (score.outline() > bestOutline) {
                bestOutline = score.outline();
                bestContent = content;
                bestAi = score.ai();
            }
            if (score.unavailable()) {
                // 拿不到分数就不装成达标：明确记一笔，本轮不做贴合度重写
                log.warn("[auto-write] chapter analysis unavailable, skip fidelity rewrite chapterId={} no={}",
                        chapterId, chapterNo);
                break;
            }
            if (passOutline(score)) {
                if (attempt > 1) {
                    log.info("[auto-write] patch passed chapterId={} no={} attempt={}/{} outline={} misses={}",
                            chapterId, chapterNo, attempt, maxBodyRegen, score.outline(), score.misses().size());
                }
                break;
            }
            log.info("[auto-write] outline score {} < {} , patch {}/{} chapterId={} no={} misses={} suggestions={}",
                    score.outline(), outlineScoreThreshold, attempt, maxBodyRegen,
                    chapterId, chapterNo, score.misses().size(), score.suggestions().size());
        }

        // 最后一版不是最高分 → 写回最高分那版
        String finalContent = lastContent;
        int finalAi = lastAi;
        if (bestContent != null && !bestContent.equals(lastContent)) {
            finalContent = bestContent;
            finalAi = bestAi;
        }
        if (!analysisUnavailable && bestOutline >= 0 && bestOutline < outlineScoreThreshold) {
            log.warn("[auto-write] chapter keeps low fidelity after {} attempts chapterId={} no={} best={}",
                    maxBodyRegen, chapterId, chapterNo, bestOutline);
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

    /**
     * 贴合度是否算过。
     *
     * <p>旧口径是「分数 ≥90」，但评分本身来自另一个模型的主观打分，噪声 ±10 很常见，
     * 把 90 当硬门槛等于「大部分章节都要重写」。现在的主判据是「缺失的剧情点 ≤1」，
     * 分数只作参考线（低于参考线时，缺失点少且分数不算难看也放行）。</p>
     */
    private boolean passOutline(ScoreResult score) {
        int misses = score.misses() == null ? 0 : score.misses().size();
        return passOutline(score.outline(), misses);
    }

    private boolean passOutline(int outline, int missCount) {
        if (outline >= outlineScoreThreshold) {
            return true;
        }
        return missCount <= 1 && outline >= Math.max(60, outlineScoreThreshold - 10);
    }

    /**
     * 定向修补：以上一版正文为底稿，只改判定没做到的地方。
     *
     * <p>走「按分析修改」那条链路（本来就能在现稿上改），而不是让正文模型从零重写：
     * 整章重采样是同模型、同上下文、同样期望值，上一版犯的错这一版大概率还犯，还多花一份 2500 字。</p>
     *
     * <p>模型有时会"顺手"把整章重写一遍，那样就退化成重采样了。所以修补后比一次段落保留率，
     * 保留率过低视为没按指令修补，回退成整章重写一次（只回退一次，不再循环）。</p>
     */
    private String patchBody(Long chapterId, int targetWords, String previous,
                             List<String> misses, List<String> suggestions) {
        String instruction = patchInstruction(misses, suggestions);
        if (previous == null || previous.isBlank()) {
            return contentOf(generateBodyWithRetry(chapterId, targetWords, instruction));
        }
        try {
            ChapterReviseRequest request = new ChapterReviseRequest();
            // 缺失点少就只动点名的段落；缺失点多才允许补写过渡
            request.setIntensity(misses != null && misses.size() >= 3 ? "MEDIUM" : "LIGHT");
            request.setInstruction(instruction);
            request.setTargetWords(targetWords);
            ChapterReviseVO revised = chapterReviseService.revise(chapterId, request);
            String patched = revised == null ? null : revised.revisedContent();
            if (patched == null || patched.isBlank()) {
                log.warn("[auto-write] patch empty, fallback to regenerate chapterId={}", chapterId);
                return contentOf(generateBodyWithRetry(chapterId, targetWords, instruction));
            }
            double keep = paragraphKeepRatio(previous, patched);
            if (keep < MIN_PATCH_KEEP_RATIO) {
                log.warn("[auto-write] patch rewrote too much (kept {}%), fallback to regenerate chapterId={}",
                        Math.round(keep * 100), chapterId);
                return contentOf(generateBodyWithRetry(chapterId, targetWords, instruction));
            }
            return patched;
        } catch (BizException ex) {
            log.warn("[auto-write] patch failed chapterId={} reason={}, fallback to regenerate",
                    chapterId, ex.getMessage());
            return contentOf(generateBodyWithRetry(chapterId, targetWords, instruction));
        }
    }

    private String contentOf(ContentGenerateResultVO generated) {
        return generated == null ? null : generated.content();
    }

    /**
     * 段落保留率：修补后有多少段是"原来那段"。
     *
     * <p>不追求精确 diff——只要判断「模型是改了该改的几段，还是把整章推倒重写」。
     * 用段首 16 个字符做指纹（去掉空白与标点），改写措辞但保留内容时仍然能对上。</p>
     */
    private double paragraphKeepRatio(String before, String after) {
        List<String> oldParts = splitParagraphs(before);
        if (oldParts.isEmpty()) {
            return 1.0D;
        }
        Set<String> newKeys = new HashSet<>();
        for (String part : splitParagraphs(after)) {
            newKeys.add(paragraphKey(part));
        }
        int kept = 0;
        for (String part : oldParts) {
            if (newKeys.contains(paragraphKey(part))) {
                kept++;
            }
        }
        return (double) kept / oldParts.size();
    }

    private List<String> splitParagraphs(String text) {
        List<String> parts = new ArrayList<>();
        if (text == null) {
            return parts;
        }
        for (String line : text.split("\\n\\s*\\n|\\r\\n\\s*\\r\\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts;
    }

    private String paragraphKey(String paragraph) {
        String text = paragraph.replaceAll("[\\s\\p{P}\\p{S}]", "");
        return text.length() <= 16 ? text : text.substring(0, 16);
    }

    /**
     * 修补指令：把上一版被判定「没写到」的剧情点和修改建议写清楚，并明确要求**只改这些**。
     *
     * <p>只给「漏了什么」而不给「其余保留」时，模型倾向于整章重写；两种要求必须同时给。</p>
     */
    private String patchInstruction(List<String> misses, List<String> suggestions) {
        StringBuilder hint = new StringBuilder("这一版在上一版正文上修改，只改下面点名的地方：\n");
        boolean hasPoint = false;
        if (misses != null && !misses.isEmpty()) {
            hint.append("漏掉的剧情点：").append(String.join("；", head(misses, 6))).append("\n");
            hasPoint = true;
        }
        if (suggestions != null && !suggestions.isEmpty()) {
            hint.append("修改建议：").append(String.join("；", head(suggestions, 4))).append("\n");
            hasPoint = true;
        }
        if (!hasPoint) {
            hint.append("上一版把本章大纲的关键动作写成了概述，没有落到具体场面与对话上。\n");
        }
        hint.append("""
                【保留要求】没有被点名的段落原样保留：不要重写、不要调整叙述顺序、不要改人物设定与文风；
                修改后总字数与上一版相差不超过 10%。
                上面点名要补的内容必须真正发生、真正写出来（该出场的人物出场、该付的代价付掉），不要用一句话交代过去。""");
        return hint.toString();
    }

    private List<String> head(List<String> values, int max) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.size() <= max ? values : values.subList(0, max);
    }

    /** 生成正文：失败重试 {@value #AI_RETRY_TIMES} 次，仍失败则抛出（本轮到此为止）。 */
    private ContentGenerateResultVO generateBodyWithRetry(Long chapterId, int targetWords, String requirement) {
        BizException last = null;
        for (int i = 0; i <= AI_RETRY_TIMES; i++) {
            try {
                ContentGenerateRequest request = new ContentGenerateRequest();
                request.setMode("REPLACE");
                request.setTargetWords(targetWords);
                request.setUseSearch(Boolean.FALSE);
                if (requirement != null && !requirement.isBlank()) {
                    request.setPrompt(requirement);
                }
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

    /**
     * 分析本章并取分数与改进点。
     *
     * <p>顺序校验不通过时<b>不能装成达标</b>（那会让低分章节永远不重写），
     * 而是标为「分析不可用」，由调用方跳过贴合度重写并记日志。</p>
     */
    private ScoreResult analyzeScores(Long chapterId, int targetWords) {
        BizException last = null;
        for (int i = 0; i <= AI_RETRY_TIMES; i++) {
            try {
                ChapterAnalysisRequest request = new ChapterAnalysisRequest();
                request.setTargetWords(targetWords);
                ChapterAnalysisVO vo = chapterAnalysisService.analyze(chapterId, request);
                int outline = vo == null || vo.outlineScore() == null ? -1 : vo.outlineScore();
                int ai = vo == null || vo.aiScore() == null ? -1 : vo.aiScore();
                return new ScoreResult(outline, ai, outline < 0,
                        vo == null || vo.outlineMisses() == null ? List.of() : vo.outlineMisses(),
                        vo == null || vo.outlineSuggestions() == null ? List.of() : vo.outlineSuggestions());
            } catch (BizException ex) {
                if (ex.getErrorCode() == ErrorCode.CHAPTER_ORDER_VIOLATION) {
                    log.warn("[auto-write] analyze skipped (prior chapter not analyzed) chapterId={} reason={}",
                            chapterId, ex.getMessage());
                    return ScoreResult.notAvailable();
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

    /**
     * 一次分析的结论。
     *
     * @param outline   大纲贴合度，-1 表示没拿到
     * @param ai        AI 率，-1 表示没拿到
     * @param unavailable 分析本身没跑成（例如前置章节还没分析），不能当成达标
     * @param misses    模型指出的「大纲里有、正文没写」的剧情点
     * @param suggestions 模型给出的修改建议
     */
    private record ScoreResult(int outline, int ai, boolean unavailable,
                               List<String> misses, List<String> suggestions) {

        /** 分析没跑成：分数拿不到，不能当成达标。 */
        static ScoreResult notAvailable() {
            return new ScoreResult(-1, -1, true, List.of(), List.of());
        }
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
        if (remaining <= DEFAULT_PLANNED_OUTLINES) {
            log.info("[auto-write] closing B: generate ending arc novelId={} remaining={}", novelId, remaining);
            MasterOutlineGenerateRequest request = new MasterOutlineGenerateRequest();
            request.setCount(1);
            request.setPrompt("这是全书的收尾：请生成一条「结局向」的总纲，明确把主线冲突收束、"
                    + "交代主要人物归宿，后续章节将在 " + DEFAULT_PLANNED_OUTLINES + " 章内完结。");
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

    /**
     * 可以写正文的章节：已规划、未写正文，且按章号升序。
     *
     * <p>只放行「当前总纲及更早总纲」下的章节——后面的总纲还没轮到，即使有人手工给它排了大纲，
     * 也不该抢在前面写，否则剧情会跳。没关联总纲的章节（手工建的）一并放行。</p>
     */
    private List<ChapterEntity> writableChapters(Long novelId) {
        List<MasterOutlineEntity> masters = masterOutlineService.ordered(novelId);
        if (masters.isEmpty()) {
            return pendingChapters(novelId, null);
        }
        int boundary = masters.get(masters.size() - 1).getSortNo() == null
                ? 0 : masters.get(masters.size() - 1).getSortNo();
        for (MasterOutlineEntity master : masters) {
            if (!MasterOutlineEntity.STATUS_DONE.equals(master.getStatus())) {
                boundary = master.getSortNo() == null ? 0 : master.getSortNo();
                break;
            }
        }
        List<Long> allowed = new ArrayList<>();
        for (MasterOutlineEntity master : masters) {
            int sortNo = master.getSortNo() == null ? 0 : master.getSortNo();
            if (sortNo <= boundary) {
                allowed.add(master.getId());
            }
        }
        return pendingChapters(novelId, allowed);
    }

    /** 已规划、未写正文的章节，按章号升序；{@code masterIds} 为 null 表示不限制归属。 */
    private List<ChapterEntity> pendingChapters(Long novelId, List<Long> masterIds) {
        return chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .and(w -> w.isNull(ChapterEntity::getWordCount).or().le(ChapterEntity::getWordCount, 0))
                .and(masterIds != null && !masterIds.isEmpty(),
                        w -> w.isNull(ChapterEntity::getMasterOutlineId)
                                .or().in(ChapterEntity::getMasterOutlineId, masterIds))
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
