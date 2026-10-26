package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.ai.service.PromptTemplateService;
import com.storyforge.modules.bookshelf.dto.ConsistencyCheckRequest;
import com.storyforge.modules.bookshelf.dto.ConsistencyDraft;
import com.storyforge.modules.bookshelf.dto.ConsistencyIssueStatusRequest;
import com.storyforge.modules.bookshelf.dto.ConsistencyIssueType;
import com.storyforge.modules.bookshelf.dto.ConsistencyIssueVO;
import com.storyforge.modules.bookshelf.dto.ConsistencyRunVO;
import com.storyforge.modules.bookshelf.dto.ConsistencyTaskVO;
import com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity;
import com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity;
import com.storyforge.modules.bookshelf.entity.ChapterCharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.ConsistencyIssueEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import com.storyforge.modules.bookshelf.mapper.ChangeHistoryMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterAnalysisMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterCharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterEventMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ConsistencyIssueMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import com.storyforge.modules.bookshelf.mapper.SettingDocMapper;
import com.storyforge.modules.bookshelf.mapper.TimelineEventMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一致性 / 矛盾检查：规则层 + AI 层，结果落到 {@code t_consistency_issue}。
 *
 * <p>两层各自解决不同的问题，不互相替代：</p>
 * <ul>
 *   <li><b>规则层</b>查「结构化硬伤」——死人出场、毁掉的物品还有数量、章号断号、悬空关联。
 *       这些用 SQL 比对就能 100% 确定，秒级、零 token，且每条都能给出可核对的证据。</li>
 *   <li><b>AI 层</b>查「语义矛盾」——称谓前后不一、伏笔没回收、时间线倒流、写了与设定冲突的能力。
 *       这些没有结构化字段可查，只能让模型读正文。</li>
 * </ul>
 *
 * <p>为什么<b>两层都落同一张表</b>：作者只关心「这本书现在有哪些问题」，不关心是谁查出来的。
 * 合流后再按严重度排序，P0 一定在最上面。</p>
 *
 * <p>为什么<b>结果落库而不是每次现算</b>：要能「忽略」。误报一次如果每次重跑都弹回来，
 * 这个页面就没用了。{@code status} 给作者一个「我看过了」的位置。</p>
 *
 * <p>AI 检查是异步任务（一次要读几十章正文、调好几次模型）；规则检查是同步接口，
 * 打开页面就能秒出——先给能确定的，再让用户决定要不要花 token 做深检。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsistencyService {

    private static final long TASK_TTL_MS = 30 * 60 * 1000L;
    /** 一次 AI 调用读几章：读太少看不出跨章矛盾，读太多 token 吃不消 */
    private static final int AI_BLOCK_SIZE = 5;
    private static final int MAX_AI_BLOCKS = 12;
    private static final int MAX_ISSUES_PER_CALL = 8;
    private static final int TIMEOUT_MS = 180_000;
    private static final double TEMPERATURE = 0.2;
    private static final int MAX_TOKENS = 4000;

    private static final String STATUS_RUNNING = "RUNNING";
    private static final String STATUS_SUCCEEDED = "SUCCEEDED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_CANCELLED = "CANCELLED";

    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final CharacterMapper characterMapper;
    private final ItemMapper itemMapper;
    private final TimelineEventMapper timelineEventMapper;
    private final ChapterCharacterMapper chapterCharacterMapper;
    private final ChapterEventMapper chapterEventMapper;
    private final ChangeHistoryMapper changeHistoryMapper;
    private final ChapterAnalysisMapper chapterAnalysisMapper;
    private final SettingDocMapper settingDocMapper;
    private final ConsistencyIssueMapper issueMapper;

    private final ConsistencyRuleChecker ruleChecker;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final PromptTemplateService promptTemplateService;
    private final ObjectMapper objectMapper;

    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    /** 每本书同时只允许一个体检任务在跑 */
    private final Map<Long, String> runningByNovel = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger();

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "consistency-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /* ------------------------------------------------------------------ */
    /* 同步：只跑规则                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * 只跑规则层，秒级返回。
     *
     * <p>打开体检页时先调它：不给作者任何等待就能看到确定的硬伤，AI 深检由用户再点一次。</p>
     */
    @Transactional
    public List<ConsistencyIssueVO> checkRules(Long novelId) {
        requireNovel(novelId);
        String runId = newRunId();
        List<ChapterEntity> chapters = orderedChapters(novelId);
        List<ConsistencyDraft> drafts = ruleChecker.check(chapters,
                loadCharacters(novelId), loadItems(novelId), loadEvents(novelId),
                loadChapterCharacters(novelId), loadChapterEvents(novelId),
                loadLifeChanges(novelId), loadAnalyzedChapterIds(novelId));
        int saved = persist(novelId, runId, drafts, UserContextHolder.requireUserId(), new HashSet<>());
        log.info("[consistency] rules done novelId={} runId={} issues={}", novelId, runId, saved);
        return loadIssues(novelId, null, null, runId);
    }

    /* ------------------------------------------------------------------ */
    /* 异步：规则 + AI                                                     */
    /* ------------------------------------------------------------------ */

    public ConsistencyTaskVO start(Long novelId, ConsistencyCheckRequest request, Long userId) {
        purgeExpired();
        ConsistencyCheckRequest safe = request == null ? new ConsistencyCheckRequest() : request;
        NovelEntity novel = requireNovel(novelId);

        // 端点与章节范围都在调用线程校验完：配置缺失要立刻报错，别等任务跑起来才发现
        endpointResolver.resolve(AiScene.CONSISTENCY_CHECK);
        List<ChapterEntity> chapters = orderedChapters(novelId);
        List<ChapterEntity> targets = resolveTargets(novelId, chapters, safe);
        if (targets.isEmpty()) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND, "这本书还没有可用于检查的正文");
        }

        String taskId = "ct" + UUID.randomUUID().toString().replace("-", "");
        String existing = runningByNovel.putIfAbsent(novelId, taskId);
        if (existing != null) {
            throw BizException.of(ErrorCode.CONSISTENCY_TASK_RUNNING);
        }

        String runId = newRunId();
        List<List<ChapterEntity>> blocks = blocks(targets);
        Task task = new Task(taskId, novelId, userId, runId, safe.safeScope(), scopeLabel(safe, targets),
                blocks.size(), UserContextHolder.require());
        tasks.put(taskId, task);
        try {
            executor.submit(() -> run(task, novel, chapters, blocks, safe));
        } catch (RejectedExecutionException ex) {
            tasks.remove(taskId);
            runningByNovel.remove(novelId, taskId);
            throw BizException.of(ErrorCode.AI_CALL_FAILED, "当前检查任务过多，请稍后再试");
        }
        log.info("[consistency] started taskId={} novelId={} scope={} chapters={} blocks={}",
                taskId, novelId, task.scope, targets.size(), blocks.size());
        return toVO(task);
    }

    public ConsistencyTaskVO status(String taskId, Long userId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        return toVO(task);
    }

    /** 请求停止：置标记，工作线程在块与块之间退出。 */
    public ConsistencyTaskVO cancel(String taskId, Long userId) {
        Task task = taskId == null ? null : tasks.get(taskId);
        if (task == null || !task.userId.equals(userId)) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        task.cancelRequested = true;
        log.info("[consistency] cancel requested taskId={}", taskId);
        return toVO(task);
    }

    private void run(Task task, NovelEntity novel, List<ChapterEntity> allChapters,
                     List<List<ChapterEntity>> blocks, ConsistencyCheckRequest request) {
        UserContextHolder.set(task.context);
        try {
            // 阶段一：规则层（全书，与 scope 无关——规则本来就是全量比对）
            task.stepLabel = "正在跑规则检查";
            List<ConsistencyDraft> drafts = new ArrayList<>(ruleChecker.check(allChapters,
                    loadCharacters(task.novelId), loadItems(task.novelId), loadEvents(task.novelId),
                    loadChapterCharacters(task.novelId), loadChapterEvents(task.novelId),
                    loadLifeChanges(task.novelId), loadAnalyzedChapterIds(task.novelId)));
            task.ruleCount = persist(task.novelId, task.runId, drafts, task.userId, task.seen);
            task.percent = Math.min(50, 10 + task.blockDone * 40 / Math.max(1, task.blockTotal));

            // 阶段二：AI 层，按块推进
            int aiCount = 0;
            int failedBlocks = 0;
            for (int i = 0; i < blocks.size(); i++) {
                if (task.cancelRequested) {
                    task.finish(STATUS_CANCELLED, aiCount,
                            "已停止，规则检查的 " + task.ruleCount + " 条已保留，AI 深检查完 " + task.blockDone + " 块后停下");
                    return;
                }
                List<ChapterEntity> block = blocks.get(i);
                task.stepLabel = "正在读" + blockScopeLabel(block);
                task.blockDone = i;
                try {
                    List<ConsistencyDraft> found = callAi(novel, allChapters, block, task.scopeLabel);
                    aiCount += persist(task.novelId, task.runId, found, task.userId, task.seen);
                } catch (BizException ex) {
                    failedBlocks++;
                    task.errorMessage = ex.getMessage();
                    log.warn("[consistency] ai block failed taskId={} block={} code={} reason={}",
                            task.id, i, ex.getErrorCode(), ex.getMessage());
                } catch (RuntimeException ex) {
                    failedBlocks++;
                    task.errorMessage = ex.getMessage() == null || ex.getMessage().isBlank()
                            ? "AI 调用失败" : ex.getMessage();
                    log.warn("[consistency] ai block failed taskId={} block={} reason={}", task.id, i,
                            ex.toString());
                }
                task.blockDone = i + 1;
                task.percent = Math.min(99, 10 + task.blockDone * 90 / Math.max(1, task.blockTotal));
            }
            task.aiCount = aiCount;
            String message = failedBlocks == 0 ? null
                    : "AI 深检有 " + failedBlocks + " 块失败，规则层的 " + task.ruleCount + " 条结果不受影响";
            task.finish(failedBlocks == 0 ? STATUS_SUCCEEDED : STATUS_PARTIAL, aiCount, message);
            log.info("[consistency] finished taskId={} rules={} ai={} failedBlocks={}",
                    task.id, task.ruleCount, aiCount, failedBlocks);
        } catch (RuntimeException ex) {
            task.finish(STATUS_FAILED, task.aiCount,
                    ex.getMessage() == null ? "检查失败" : ex.getMessage());
            log.warn("[consistency] unexpected failure taskId={}: {}", task.id, ex.toString());
        } finally {
            runningByNovel.remove(task.novelId, task.id);
            UserContextHolder.clear();
        }
    }

    /* ------------------------------------------------------------------ */
    /* AI 层                                                              */
    /* ------------------------------------------------------------------ */

    private List<ConsistencyDraft> callAi(NovelEntity novel, List<ChapterEntity> allChapters,
                                          List<ChapterEntity> block, String scopeLabel) {
        AiEndpoint base = endpointResolver.resolve(AiScene.CONSISTENCY_CHECK);
        AiEndpoint endpoint = new AiEndpoint(base.baseUrl(), base.apiKey(), base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS), base.extraHeaders(), TEMPERATURE, MAX_TOKENS);

        String title = novel.getTitle() == null || novel.getTitle().isBlank() ? "这本书" : novel.getTitle();
        String genre = novel.getGenre() == null ? "" : novel.getGenre();
        Map<String, String> vars = new HashMap<>();
        vars.put("novelTitle", title);
        vars.put("genre", genre.isBlank() ? "通用" : genre);
        vars.put("scopeLabel", blockScopeLabel(block));
        String system = promptTemplateService.system(AiScene.CONSISTENCY_CHECK, vars);
        if (system.isBlank()) {
            system = fallbackSystem(title, blockScopeLabel(block));
        }
        String user = aiUserPrompt(novel, allChapters, block);

        ChatResult reply = aiUsageService.chat(AiScene.CONSISTENCY_CHECK, endpoint,
                List.of(ChatMessage.system(system), ChatMessage.user(user)));
        JsonNode root = parse(reply.content());
        if (root == null) {
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, "AI 返回的内容不是合法 JSON");
        }
        return parseIssues(root, block);
    }

    private String fallbackSystem(String novelTitle, String blockScope) {
        return """
                你是《%s》的连贯性编辑，负责在正文里找出自相矛盾的地方。
                这次的检查范围：%s。
                只报读者能一眼看出不对的硬矛盾，每条都要给出正文原句作为证据，给不出证据就不要报。
                最多 8 条，按严重度排序；没发现就返回空数组。
                """.formatted(novelTitle, blockScope);
    }

    private String aiUserPrompt(NovelEntity novel, List<ChapterEntity> allChapters,
                                List<ChapterEntity> block) {
        StringBuilder builder = new StringBuilder();
        builder.append("【作品】").append(nvl(novel.getTitle()));
        if (hasText(novel.getGenre())) {
            builder.append("（").append(novel.getGenre()).append("）");
        }
        builder.append('\n');

        builder.append("【设定摘要】\n");
        for (SettingDocEntity doc : loadSettings(novel.getId())) {
            String content = doc.getContentMd();
            if (!hasText(content)) {
                continue;
            }
            builder.append("- ").append(nvl(doc.getTitle())).append("：")
                    .append(clip(content, 300)).append('\n');
        }

        List<CharacterEntity> characters = loadCharacters(novel.getId());
        builder.append("【人物档案】姓名｜身份｜定位｜生死｜首次登场\n");
        if (characters.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            for (CharacterEntity character : characters) {
                builder.append("- ").append(character.getName())
                        .append('｜').append(nvl(character.getIdentity()))
                        .append('｜').append(NovelLabels.label(NovelLabels.ROLE, character.getRolePos()))
                        .append('｜').append(NovelLabels.label(NovelLabels.LIFE, character.getLifeStatus()))
                        .append('｜').append(character.getFirstChapter() == null ? "未记录"
                                : "第 " + character.getFirstChapter() + " 章")
                        .append('\n');
            }
        }

        builder.append("【物品档案】名称｜持有者｜状态｜数量\n");
        List<ItemEntity> items = loadItems(novel.getId());
        if (items.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            for (ItemEntity item : items) {
                builder.append("- ").append(item.getName())
                        .append('｜').append(nvl(item.getOwner()))
                        .append('｜').append(NovelLabels.label(NovelLabels.ITEM_STATUS, item.getStatus()))
                        .append('｜').append(item.getQuantity() == null ? "不计" : item.getQuantity())
                        .append('\n');
            }
        }

        builder.append("【事件档案】时间｜标题｜状态\n");
        List<TimelineEventEntity> events = loadEvents(novel.getId());
        if (events.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            for (TimelineEventEntity event : events) {
                builder.append("- ").append(nvl(event.getEventTime()))
                        .append('｜').append(nvl(event.getTitle()))
                        .append('｜').append(NovelLabels.label(NovelLabels.EVENT_STATUS, event.getStatus()))
                        .append('\n');
            }
        }

        // 块之前的章节只给摘要：模型需要知道前情，但不需要把全文再读一遍
        Integer firstNo = block.get(0).getChapterNo();
        List<ChapterEntity> before = new ArrayList<>();
        for (ChapterEntity chapter : allChapters) {
            if (chapter.getChapterNo() != null && firstNo != null && chapter.getChapterNo() < firstNo
                    && chapter.getWordCount() != null && chapter.getWordCount() > 0) {
                before.add(chapter);
            }
        }
        if (!before.isEmpty()) {
            List<ChapterEntity> tail = before.subList(Math.max(0, before.size() - 5), before.size());
            builder.append("【前情摘要（更早的章节）】\n");
            for (ChapterEntity chapter : tail) {
                builder.append("- 第 ").append(chapter.getChapterNo()).append(" 章 ").append(nvl(chapter.getTitle()))
                        .append("：").append(clip(chapter.getSummary(), 160)).append('\n');
            }
        }

        builder.append("\n【本次要检查的正文】\n");
        for (ChapterEntity chapter : block) {
            builder.append("=== 第 ").append(chapter.getChapterNo()).append(" 章 ")
                    .append(nvl(chapter.getTitle())).append(" ===\n");
            builder.append("大纲：").append(hasText(chapter.getSummary()) ? clip(chapter.getSummary(), 300) : "（无）")
                    .append('\n');
            builder.append(clip(chapter.getContent(), 6000)).append("\n\n");
        }

        builder.append("""
                找出这些章节里的自相矛盾。规则层已经查过的（死人出场、物品数量、首次登场章、
                废弃事件、章号与排序、悬空关联）不要重复报。
                每条给出 type / severity / chapterNo / title / detail / evidence / suggestion。
                最多 %d 条，按严重度从重到轻；没有就返回 {"issues": []}。
                """.formatted(MAX_ISSUES_PER_CALL));
        return builder.toString();
    }

    private List<ConsistencyDraft> parseIssues(JsonNode root, List<ChapterEntity> block) {
        List<ConsistencyDraft> found = new ArrayList<>();
        JsonNode array = root.get("issues");
        if (array == null || !array.isArray()) {
            return found;
        }
        Map<Integer, ChapterEntity> byNo = new LinkedHashMap<>();
        for (ChapterEntity chapter : block) {
            byNo.put(chapter.getChapterNo(), chapter);
        }
        for (JsonNode node : array) {
            if (found.size() >= MAX_ISSUES_PER_CALL) {
                break;
            }
            ConsistencyIssueType type = ConsistencyIssueType.of(text(node, "type"));
            if (type == null || !type.isAiAllowed()) {
                log.info("[consistency] dropped issue with unsupported type: {}", text(node, "type"));
                continue;
            }
            String severity = normalizeSeverity(text(node, "severity"));
            String title = text(node, "title");
            if (!hasText(title)) {
                log.info("[consistency] dropped issue without title");
                continue;
            }
            // 没有依据的一律丢弃：一条说不清「凭什么这么说」的问题是噪音，
            // 作者既没法核对也没法修。提示词里已经写明「给不出证据就不要报」，这里兜底。
            String evidence = text(node, "evidence");
            if (!hasText(evidence)) {
                log.info("[consistency] dropped issue without evidence: {}", title);
                continue;
            }
            Integer chapterNo = intOf(node, "chapterNo");
            ChapterEntity chapter = chapterNo == null ? null : byNo.get(chapterNo);
            found.add(new ConsistencyDraft("AI",
                    chapter == null ? null : chapter.getId(),
                    chapter == null ? chapterNo : chapter.getChapterNo(),
                    chapter == null ? null : chapter.getTitle(),
                    type.name(), severity,
                    title,
                    text(node, "detail"),
                    evidence,
                    text(node, "suggestion")));
        }
        return found;
    }

    /* ------------------------------------------------------------------ */
    /* 清单与状态                                                          */
    /* ------------------------------------------------------------------ */

    public List<ConsistencyIssueVO> list(Long novelId, String status, String severity, String runId) {
        requireNovel(novelId);
        return loadIssues(novelId, status, severity, runId);
    }

    /** 最近 10 次体检的批次摘要。 */
    public List<ConsistencyRunVO> runs(Long novelId) {
        requireNovel(novelId);
        List<ConsistencyIssueEntity> rows = issueMapper.selectList(
                Wrappers.<ConsistencyIssueEntity>lambdaQuery()
                        .eq(ConsistencyIssueEntity::getNovelId, novelId)
                        .orderByDesc(ConsistencyIssueEntity::getCreatedAt)
                        .last("LIMIT 500"));
        Map<String, List<ConsistencyIssueEntity>> grouped = new LinkedHashMap<>();
        for (ConsistencyIssueEntity row : rows) {
            grouped.computeIfAbsent(row.getRunId(), key -> new ArrayList<>()).add(row);
        }
        List<ConsistencyRunVO> result = new ArrayList<>();
        for (Map.Entry<String, List<ConsistencyIssueEntity>> entry : grouped.entrySet()) {
            if (result.size() >= 10) {
                break;
            }
            List<ConsistencyIssueEntity> list = entry.getValue();
            int p0 = 0;
            int p1 = 0;
            int p2 = 0;
            int open = 0;
            boolean withAi = false;
            LocalDateTime createdAt = null;
            for (ConsistencyIssueEntity row : list) {
                if ("P0".equalsIgnoreCase(row.getSeverity())) {
                    p0++;
                } else if ("P1".equalsIgnoreCase(row.getSeverity())) {
                    p1++;
                } else {
                    p2++;
                }
                if ("OPEN".equalsIgnoreCase(row.getStatus())) {
                    open++;
                }
                if ("AI".equalsIgnoreCase(row.getSource())) {
                    withAi = true;
                }
                if (createdAt == null || (row.getCreatedAt() != null && row.getCreatedAt().isAfter(createdAt))) {
                    createdAt = row.getCreatedAt();
                }
            }
            result.add(new ConsistencyRunVO(entry.getKey(), withAi, null,
                    withAi ? "规则 + AI 深检" : "仅规则检查",
                    createdAt, list.size(), p0, p1, p2, open));
        }
        return result;
    }

    @Transactional
    public ConsistencyIssueVO updateStatus(Long issueId, ConsistencyIssueStatusRequest request, Long userId) {
        ConsistencyIssueEntity row = issueMapper.selectById(issueId);
        // 归属二次校验：数据隔离插件已追加 user_id 条件，这里再兜一次——
        // 按 ID 直达的接口必须有显式校验，越权统一表现为「资源不存在」。
        if (row == null || !userId.equals(row.getUserId())) {
            throw BizException.of(ErrorCode.CONSISTENCY_ISSUE_NOT_FOUND);
        }
        String status = normalizeStatus(request == null ? null : request.getStatus());
        if (status == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "状态只能是 OPEN / IGNORED / FIXED");
        }
        row.setStatus(status);
        issueMapper.updateById(row);
        return toVO(row);
    }

    /* ------------------------------------------------------------------ */
    /* 落库与查询                                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 落库并去重（同一批次内指纹相同的只留一条），返回实际写入条数。
     *
     * <p>{@code seen} 由调用方持有并贯穿整个批次：AI 层是按块滚动的，
     * 同一个问题完全可能被相邻两块各报一次（模型只看到局部），去重集合必须跨块共用，
     * 否则作者会看到两条一模一样的问题。</p>
     *
     * <p>{@code userId} 必须显式传入：{@code t_consistency_issue.user_id} 是 NOT NULL，
     * 而审计字段填充器对 {@code userId} 的自动填充在本实体上不生效
     * （它与全工程其他实体一样，靠调用方写入）。</p>
     */
    @Transactional
    public int persist(Long novelId, String runId, List<ConsistencyDraft> drafts, Long userId,
                       Set<String> seen) {
        if (drafts == null || drafts.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (ConsistencyDraft draft : drafts) {
            if (draft.title() == null || draft.title().isBlank()) {
                continue;
            }
            ConsistencyDraft clamped = ConsistencyRuleChecker.clamp(draft);
            if (!seen.add(clamped.fingerprint())) {
                continue;
            }
            ConsistencyIssueEntity entity = new ConsistencyIssueEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setRunId(runId);
            entity.setSource("AI".equalsIgnoreCase(clamped.source()) ? "AI" : "RULE");
            entity.setChapterId(clamped.chapterId());
            entity.setChapterNo(clamped.chapterNo());
            entity.setChapterTitle(clamped.chapterTitle());
            entity.setIssueType(ConsistencyIssueType.of(clamped.issueType()) == null
                    ? ConsistencyIssueType.STRUCTURE.name() : ConsistencyIssueType.of(clamped.issueType()).name());
            entity.setSeverity(normalizeSeverity(clamped.severity()));
            entity.setTitle(clamped.title());
            entity.setDetail(clamped.detail());
            entity.setEvidence(clamped.evidence());
            entity.setSuggestion(clamped.suggestion());
            entity.setStatus(ConsistencyIssueEntity.STATUS_OPEN);
            issueMapper.insert(entity);
            saved++;
        }
        return saved;
    }

    private List<ConsistencyIssueVO> loadIssues(Long novelId, String status, String severity, String runId) {
        var query = Wrappers.<ConsistencyIssueEntity>lambdaQuery()
                .eq(ConsistencyIssueEntity::getNovelId, novelId);
        if (hasText(status)) {
            String normalized = normalizeStatus(status);
            if (normalized != null) {
                query.eq(ConsistencyIssueEntity::getStatus, normalized);
            }
        }
        if (hasText(severity)) {
            String normalized = normalizeSeverity(severity);
            query.eq(ConsistencyIssueEntity::getSeverity, normalized);
        }
        if (hasText(runId)) {
            query.eq(ConsistencyIssueEntity::getRunId, runId.trim());
        }
        List<ConsistencyIssueEntity> rows = issueMapper.selectList(
                query.orderByAsc(ConsistencyIssueEntity::getSeverity)
                        .orderByAsc(ConsistencyIssueEntity::getChapterNo)
                        .orderByDesc(ConsistencyIssueEntity::getCreatedAt)
                        .last("LIMIT 300"));
        List<ConsistencyIssueVO> result = new ArrayList<>(rows.size());
        for (ConsistencyIssueEntity row : rows) {
            result.add(toVO(row));
        }
        return result;
    }

    private ConsistencyIssueVO toVO(ConsistencyIssueEntity row) {
        ConsistencyIssueType type = ConsistencyIssueType.of(row.getIssueType());
        return new ConsistencyIssueVO(
                row.getId(),
                row.getRunId(),
                row.getSource(),
                "AI".equalsIgnoreCase(row.getSource()) ? "AI 深检" : "规则检查",
                row.getChapterId(),
                row.getChapterNo(),
                row.getChapterTitle(),
                row.getIssueType(),
                type == null ? row.getIssueType() : type.getLabel(),
                row.getSeverity(),
                severityLabel(row.getSeverity()),
                row.getTitle(),
                row.getDetail(),
                row.getEvidence(),
                row.getSuggestion(),
                row.getStatus(),
                statusLabel(row.getStatus()),
                row.getCreatedAt());
    }

    private ConsistencyTaskVO toVO(Task task) {
        List<ConsistencyIssueVO> issues = task.isFinished() && task.runId != null
                ? loadIssues(task.novelId, null, null, task.runId)
                : List.of();
        int p0 = 0;
        int p1 = 0;
        int p2 = 0;
        for (ConsistencyIssueVO issue : issues) {
            if ("P0".equals(issue.severity())) {
                p0++;
            } else if ("P1".equals(issue.severity())) {
                p1++;
            } else {
                p2++;
            }
        }
        return new ConsistencyTaskVO(
                task.id,
                task.novelId.toString(),
                task.status,
                task.percent,
                task.stepLabel,
                task.runId,
                task.scope,
                task.scopeLabel,
                task.blockTotal,
                task.blockDone,
                task.ruleCount,
                task.aiCount,
                issues.size(),
                p0, p1, p2,
                issues,
                task.message,
                task.errorMessage,
                task.startedAt,
                task.finishedAt);
    }

    /* ------------------------------------------------------------------ */
    /* 数据加载                                                            */
    /* ------------------------------------------------------------------ */

    private NovelEntity requireNovel(Long novelId) {
        NovelEntity novel = novelId == null ? null : novelMapper.selectById(novelId);
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        return novel;
    }

    private List<ChapterEntity> orderedChapters(Long novelId) {
        List<ChapterEntity> list = new ArrayList<>(chapterMapper.selectList(
                Wrappers.<ChapterEntity>lambdaQuery()
                        .eq(ChapterEntity::getNovelId, novelId)
                        .orderByAsc(ChapterEntity::getVolumeNo)
                        .orderByAsc(ChapterEntity::getChapterNo)));
        list.sort(Comparator
                .comparing((ChapterEntity chapter) -> chapter.getVolumeNo() == null ? 0 : chapter.getVolumeNo())
                .thenComparing(chapter -> chapter.getChapterNo() == null ? 0 : chapter.getChapterNo()));
        return list;
    }

    private List<CharacterEntity> loadCharacters(Long novelId) {
        return characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, novelId)
                .orderByAsc(CharacterEntity::getSortNo)
                .orderByAsc(CharacterEntity::getId));
    }

    private List<ItemEntity> loadItems(Long novelId) {
        return itemMapper.selectList(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getNovelId, novelId)
                .orderByAsc(ItemEntity::getSortNo)
                .orderByAsc(ItemEntity::getId));
    }

    private List<TimelineEventEntity> loadEvents(Long novelId) {
        return timelineEventMapper.selectList(Wrappers.<TimelineEventEntity>lambdaQuery()
                .eq(TimelineEventEntity::getNovelId, novelId)
                .orderByAsc(TimelineEventEntity::getSortNo)
                .orderByAsc(TimelineEventEntity::getId));
    }

    private List<ChapterCharacterEntity> loadChapterCharacters(Long novelId) {
        return chapterCharacterMapper.selectList(Wrappers.<ChapterCharacterEntity>lambdaQuery()
                .eq(ChapterCharacterEntity::getNovelId, novelId)
                .orderByAsc(ChapterCharacterEntity::getSortNo));
    }

    private List<ChapterEventEntity> loadChapterEvents(Long novelId) {
        return chapterEventMapper.selectList(Wrappers.<ChapterEventEntity>lambdaQuery()
                .eq(ChapterEventEntity::getNovelId, novelId)
                .orderByAsc(ChapterEventEntity::getSortNo));
    }

    /** 生死状态变更履历，用来反查「人物是在第几章死的」。 */
    private List<ChangeHistoryEntity> loadLifeChanges(Long novelId) {
        return changeHistoryMapper.selectList(Wrappers.<ChangeHistoryEntity>lambdaQuery()
                .eq(ChangeHistoryEntity::getNovelId, novelId)
                .eq(ChangeHistoryEntity::getEntityType, "CHARACTER")
                .eq(ChangeHistoryEntity::getFieldKey, "lifeStatus")
                .orderByAsc(ChangeHistoryEntity::getChapterNo));
    }

    private Set<Long> loadAnalyzedChapterIds(Long novelId) {
        Set<Long> ids = new HashSet<>();
        for (ChapterAnalysisEntity row : chapterAnalysisMapper.selectList(
                Wrappers.<ChapterAnalysisEntity>lambdaQuery()
                        .eq(ChapterAnalysisEntity::getNovelId, novelId))) {
            ids.add(row.getChapterId());
        }
        return ids;
    }

    private List<SettingDocEntity> loadSettings(Long novelId) {
        return settingDocMapper.selectList(Wrappers.<SettingDocEntity>lambdaQuery()
                .eq(SettingDocEntity::getNovelId, novelId)
                .orderByAsc(SettingDocEntity::getSort)
                .orderByAsc(SettingDocEntity::getId));
    }

    /* ------------------------------------------------------------------ */
    /* 范围与分块                                                          */
    /* ------------------------------------------------------------------ */

    /** AI 检查的目标章节：只挑有正文的章节，没写过的章节没什么可查。 */
    private List<ChapterEntity> resolveTargets(Long novelId, List<ChapterEntity> chapters,
                                               ConsistencyCheckRequest request) {
        List<ChapterEntity> written = new ArrayList<>();
        for (ChapterEntity chapter : chapters) {
            if (chapter.getWordCount() != null && chapter.getWordCount() > 0
                    && chapter.getContent() != null && !chapter.getContent().isBlank()) {
                written.add(chapter);
            }
        }
        String scope = request.safeScope();
        if (ConsistencyCheckRequest.SCOPE_ALL.equals(scope)) {
            return written;
        }
        if (ConsistencyCheckRequest.SCOPE_CHAPTER.equals(scope)) {
            String chapterId = request.getChapterId();
            if (chapterId == null || chapterId.isBlank()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "检查单章时必须指定章节");
            }
            for (ChapterEntity chapter : written) {
                if (chapter.getId() != null && chapter.getId().toString().equals(chapterId.trim())) {
                    return List.of(chapter);
                }
            }
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND, "这一章还没有正文，无法检查");
        }
        int count = request.safeRecentCount();
        if (written.size() <= count) {
            return written;
        }
        return new ArrayList<>(written.subList(written.size() - count, written.size()));
    }

    /** AI 层按 5 章一块滚动；块数封顶，免得一本 300 章的书一次发 60 次请求。 */
    private List<List<ChapterEntity>> blocks(List<ChapterEntity> targets) {
        List<List<ChapterEntity>> result = new ArrayList<>();
        for (int i = 0; i < targets.size() && result.size() < MAX_AI_BLOCKS; i += AI_BLOCK_SIZE) {
            result.add(new ArrayList<>(targets.subList(i, Math.min(i + AI_BLOCK_SIZE, targets.size()))));
        }
        return result;
    }

    private String scopeLabel(ConsistencyCheckRequest request, List<ChapterEntity> targets) {
        if (targets.isEmpty()) {
            return "（没有可检查的正文）";
        }
        String scope = request.safeScope();
        if (ConsistencyCheckRequest.SCOPE_CHAPTER.equals(scope)) {
            return "第 " + targets.get(0).getChapterNo() + " 章";
        }
        if (ConsistencyCheckRequest.SCOPE_ALL.equals(scope)) {
            return "全书共 " + targets.size() + " 章";
        }
        return "第 " + targets.get(0).getChapterNo() + "~" + targets.get(targets.size() - 1).getChapterNo()
                + " 章（共 " + targets.size() + " 章）";
    }

    private String blockScopeLabel(List<ChapterEntity> block) {
        Integer first = block.get(0).getChapterNo();
        if (block.size() == 1) {
            return "第 " + first + " 章";
        }
        return "第 " + first + "~" + block.get(block.size() - 1).getChapterNo() + " 章";
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                                */
    /* ------------------------------------------------------------------ */

    private JsonNode parse(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(LenientJson.repair(LenientJson.extractObject(raw)));
            return node != null && node.isObject() ? node : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }

    private static Integer intOf(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.canConvertToInt()) {
            return null;
        }
        int parsed = value.asInt();
        return parsed <= 0 ? null : parsed;
    }

    /** 严重度只允许三档，模型给别的就按 P2 处理（宁可降级也不让脏值进库）。 */
    private static String normalizeSeverity(String raw) {
        if (raw == null) {
            return ConsistencyIssueEntity.SEVERITY_P2;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "P0", "P1" -> upper;
            default -> ConsistencyIssueEntity.SEVERITY_P2;
        };
    }

    private static String normalizeStatus(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "OPEN" -> ConsistencyIssueEntity.STATUS_OPEN;
            case "IGNORED" -> ConsistencyIssueEntity.STATUS_IGNORED;
            case "FIXED" -> ConsistencyIssueEntity.STATUS_FIXED;
            default -> null;
        };
    }

    private static String severityLabel(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase(Locale.ROOT)) {
            case "P0" -> "硬伤";
            case "P1" -> "需注意";
            default -> "提示";
        };
    }

    private static String statusLabel(String status) {
        return switch (status == null ? "" : status.toUpperCase(Locale.ROOT)) {
            case "IGNORED" -> "已忽略";
            case "FIXED" -> "已修复";
            default -> "待处理";
        };
    }

    private static String newRunId() {
        return "ci" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }

    private static String clip(String value, int max) {
        if (!hasText(value)) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "……";
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
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

    /** 内存任务快照。轮询线程与工作线程并发读写，字段一律 volatile。 */
    private static final class Task {

        private final String id;
        private final Long novelId;
        private final Long userId;
        private final String runId;
        private final String scope;
        private final String scopeLabel;
        private final int blockTotal;
        private final UserContext context;
        private final LocalDateTime startedAt = LocalDateTime.now();

        /** 整个批次共用的去重集合：规则阶段与后面每一块 AI 都往里写 */
        private final Set<String> seen = new HashSet<>();

        private volatile String status = STATUS_RUNNING;
        private volatile int percent;
        private volatile String stepLabel = "正在准备";
        private volatile int blockDone;
        private volatile int ruleCount;
        private volatile int aiCount;
        private volatile String message;
        private volatile String errorMessage;
        private volatile LocalDateTime finishedAt;
        private volatile long updatedAt = System.currentTimeMillis();
        private volatile boolean cancelRequested;

        private Task(String id, Long novelId, Long userId, String runId, String scope, String scopeLabel,
                     int blockTotal, UserContext context) {
            this.id = id;
            this.novelId = novelId;
            this.userId = userId;
            this.runId = runId;
            this.scope = scope;
            this.scopeLabel = scopeLabel;
            this.blockTotal = blockTotal;
            this.context = context;
        }

        private boolean isFinished() {
            return !STATUS_RUNNING.equals(status);
        }

        private void finish(String status, int aiCount, String message) {
            this.status = status;
            this.aiCount = aiCount;
            this.percent = 100;
            this.message = message;
            this.stepLabel = switch (status) {
                case STATUS_SUCCEEDED -> "检查完成";
                case STATUS_PARTIAL -> "检查完成（AI 部分失败）";
                case STATUS_CANCELLED -> "已停止";
                default -> "检查失败";
            };
            this.finishedAt = LocalDateTime.now();
            this.updatedAt = System.currentTimeMillis();
        }
    }
}
