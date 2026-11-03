package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WordCounts;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.infra.text.AiFlavorMeter;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.AnalysisDraft;
import com.storyforge.modules.bookshelf.dto.AnalysisFacts;
import com.storyforge.modules.bookshelf.dto.AnalysisScores;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisOverviewVO;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisRequest;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisSummaryVO;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisVO;
import com.storyforge.modules.bookshelf.dto.ChangeHistoryVO;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterAnalysisMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.ChangeHistoryMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import com.storyforge.modules.novel.mapper.NovelMapper;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 章节分析：按章节顺序逐章体检。
 *
 * <p>分析必须按顺序进行：只有前面的章节都已有分析记录，当前章才能分析，
 * 这样「新出现的人物」「刚结束的事件」这类判断才有可靠的前置状态。</p>
 *
 * <p>六件事：</p>
 * <ol>
 *   <li>把本章新出现的人物补进人物档案；</li>
 *   <li>把人物 / 物品 / 势力的状态变化写进变更履历（章节、原状态、现状态、正文依据）；</li>
 *   <li>把本章收尾的事件状态改成已完成；</li>
 *   <li>按汉字数核对字数是否达标；</li>
 *   <li>评审正文与本章大纲的贴合度，0~100 分；</li>
 *   <li>检测 AI 味，0~100 分（0 分为纯 AI 味）。</li>
 * </ol>
 *
 * <p>事实抽取与质量评审是两个独立调用，并行执行；本地统计（套话密度、句首重复、段落节奏）
 * 既作为评审证据，也给模型评分设上限，避免 AI 互评互相放水。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterAnalysisService {

    private static final int TIMEOUT_MS = 300_000;
    private static final int DEFAULT_TARGET_WORDS = 2500;
    private static final int MIN_TARGET_WORDS = 300;
    private static final int MAX_TARGET_WORDS = 6000;
    /** 目标字数上下浮动 15% 记为达标 */
    private static final double PASS_RATIO = 0.15D;
    /** 超出 30% 才算明显偏多 / 偏少 */
    private static final double HEAVY_RATIO = 0.30D;
    private static final int MAX_NEW_CHARACTERS = 8;
    private static final int MAX_CHANGES_PER_TYPE = 20;
    private static final int MAX_EVENT_CHANGES = 6;
    /** 事实摘要上限：模型要求 150~250 字，留足冗余后硬截断，防一段灌水撑爆后续提示词 */
    private static final int MAX_FACT_SUMMARY_CHARS = 1200;
    /** 单章最多登记多少个角色的状态变化 */
    private static final int MAX_CHARACTER_STATES = 12;
    /** 每个角色「已知 / 被隐瞒」各最多几条 */
    private static final int MAX_KNOWS_PER_SIDE = 4;
    /** 单章最多新增 / 回收多少条伏笔 */
    private static final int MAX_FORESHADOW_PER_CHAPTER = 5;
    private static final int MAX_LIST_ITEMS = 8;
    private static final int MAX_WARNINGS = 24;
    private static final int ANALYSIS_HISTORY_LIMIT = 20;

    private static final String RETRY_SUFFIX = """

            【重试】上一次输出不是合法 JSON。请重新输出，且必须满足：
            只输出一个 JSON 对象；字符串值内不出现英文双引号与真实换行；不要 markdown 代码围栏；字段名与示例一致。
            """;

    private final NovelAssetService assetService;
    private final BookshelfService bookshelfService;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final CharacterMapper characterMapper;
    private final ItemMapper itemMapper;
    private final FactionMapper factionMapper;
    private final ChapterAnalysisMapper analysisMapper;
    private final ChangeHistoryMapper changeHistoryMapper;
    private final com.storyforge.modules.bookshelf.mapper.ForeshadowMapper foreshadowMapper;
    private final com.storyforge.modules.bookshelf.mapper.CharacterStateMapper characterStateMapper;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ChapterAnalysisPromptBuilder promptBuilder;
    private final AiFlavorMeter flavorMeter;
    private final ChapterAnalysisApplier applier;
    private final CharacterAssetService characterAssetService;
    private final ObjectMapper objectMapper;
    private final com.storyforge.modules.autowrite.support.AutoWriteGuard autoWriteGuard;

    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "chapter-analysis-" + COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /* ------------------------------------------------------------------ */
    /* 分析                                                               */
    /* ------------------------------------------------------------------ */

    public ChapterAnalysisVO analyze(Long chapterId, ChapterAnalysisRequest request) {
        UserContextHolder.requireUserId();
        ChapterVO detail = assetService.getChapter(chapterId);
        ChapterEntity chapter = chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND);
        }
        autoWriteGuard.assertEditable(chapter.getNovelId());
        if (chapter.getContent() == null || chapter.getContent().isBlank()) {
            throw BizException.of(ErrorCode.CHAPTER_CONTENT_EMPTY);
        }

        List<ChapterVO> chapters = assetService.listChapters(chapter.getNovelId());
        ensurePriorAnalyzed(chapter.getNovelId(), chapters, chapter);
        ChapterVO previous = previousOf(chapters, chapter);

        NovelDetailVO novelDetail = bookshelfService.detail(chapter.getNovelId());
        String novelTitle = novelDetail.getNovel() == null ? "" : novelDetail.getNovel().getTitle();
        String genre = novelDetail.getNovel() == null ? "" : novelDetail.getNovel().getGenre();
        // 文风基线：质检要靠它判断「腔调是否统一、该笑有没有笑」，与正文生成取的是同一段设定、同一截断长度
        String style = PromptVars.setting(novelDetail, "STYLE", 300);

        List<CharacterEntity> characters = characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, chapter.getNovelId())
                .orderByAsc(CharacterEntity::getSortNo));
        List<ItemEntity> items = itemMapper.selectList(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getNovelId, chapter.getNovelId()));
        List<FactionEntity> factions = factionMapper.selectList(Wrappers.<FactionEntity>lambdaQuery()
                .eq(FactionEntity::getNovelId, chapter.getNovelId()));
        Map<Long, String> factionNames = new HashMap<>();
        factions.forEach(faction -> factionNames.put(faction.getId(), faction.getName()));
        // 属性与背包参与事实抽取：模型要能看到当前值和数量，才能算出"变成多少"
        List<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity> attributes =
                characterAssetService.attributeEntities(chapter.getNovelId());
        List<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity> inventories =
                characterAssetService.inventoryEntities(chapter.getNovelId());
        // 未回收的伏笔要让模型看到，它才能判断「本章回收了哪条」，也才不会重复登记
        List<com.storyforge.modules.bookshelf.entity.ForeshadowEntity> openForeshadows =
                foreshadowMapper.selectList(Wrappers.<com.storyforge.modules.bookshelf.entity.ForeshadowEntity>lambdaQuery()
                        .eq(com.storyforge.modules.bookshelf.entity.ForeshadowEntity::getNovelId, chapter.getNovelId())
                        .eq(com.storyforge.modules.bookshelf.entity.ForeshadowEntity::getStatus,
                                com.storyforge.modules.bookshelf.entity.ForeshadowEntity.STATUS_OPEN)
                        .orderByAsc(com.storyforge.modules.bookshelf.entity.ForeshadowEntity::getPlantedChapterNo));

        int wordCount = WordCounts.countChinese(chapter.getContent());
        int targetWords = resolveTarget(request, chapters);
        int minWords = (int) Math.floor(targetWords * (1 - PASS_RATIO));
        int maxWords = (int) Math.ceil(targetWords * (1 + PASS_RATIO));
        boolean wordPass = wordCount >= minWords && wordCount <= maxWords;
        String wordLabel = wordLabel(wordCount, targetWords);

        AiFlavorMeter.Metrics metrics = flavorMeter.measure(chapter.getContent());
        List<String> warnings = new ArrayList<>();
        if (metrics.hanCount() < 300) {
            warnings.add("本章不足 300 个汉字，评分参考价值有限");
        }

        long startedAt = System.currentTimeMillis();
        AiEndpoint factEndpoint = endpoint(AiScene.SYNC_EXTRACT, 0.35D, 4000);
        AiEndpoint scoreEndpoint = endpoint(AiScene.ANALYSIS_GEN, 0.25D, 2600);
        String factPrompt = promptBuilder.factPrompt(chapter, novelTitle, genre, detail,
                characters, items, factions, factionNames, attributes, inventories, openForeshadows);
        String scorePrompt = promptBuilder.scorePrompt(chapter, novelTitle, genre, detail, previous,
                metrics, targetWords, minWords, maxWords, style);

        // 事实抽取与评分并发跑在两个线程里：用量统计靠 UserContextHolder 取 user_id，
        // ThreadLocal 不会自动跨线程传递，所以每个任务自己 set / clear
        UserContext context = UserContextHolder.require();
        CompletableFuture<AiJson> factFuture = CompletableFuture.supplyAsync(
                () -> {
                    UserContextHolder.set(context);
                    try {
                        return callJson(AiScene.SYNC_EXTRACT, factEndpoint,
                                promptBuilder.factSystem(novelTitle, chapter.getChapterNo()),
                                factPrompt, "fact");
                    } finally {
                        UserContextHolder.clear();
                    }
                }, executor);
        CompletableFuture<AiJson> scoreFuture = CompletableFuture.supplyAsync(
                () -> {
                    UserContextHolder.set(context);
                    try {
                        return callJson(AiScene.ANALYSIS_GEN, scoreEndpoint,
                                promptBuilder.scoreSystem(novelTitle, chapter.getChapterNo(), targetWords, style),
                                scorePrompt, "score");
                    } finally {
                        UserContextHolder.clear();
                    }
                }, executor);

        AiJson factJson;
        AiJson scoreJson;
        try {
            factJson = factFuture.join();
            scoreJson = scoreFuture.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            throw BizException.of(ErrorCode.AI_CALL_FAILED,
                    cause == null || cause.getMessage() == null ? "AI 调用失败" : cause.getMessage());
        }

        AnalysisFacts facts = parseFacts(factJson.node());
        AnalysisScores scores = parseScores(scoreJson.node());
        int cap = flavorMeter.scoreCap(metrics);
        int aiScore = Math.max(0, Math.min(100, scores.aiScore()));
        if (aiScore > cap) {
            warnings.add("模型给出 AI 率 " + aiScore + " 分，但本地检测到套话或重复句式，按规则下调到 " + cap + " 分");
            aiScore = cap;
        }

        List<ChapterAnalysisVO.LocalSignal> localSignals = flavorMeter.signals(metrics).stream()
                .map(signal -> new ChapterAnalysisVO.LocalSignal(signal.name(), signal.value(), signal.level()))
                .toList();
        List<ChapterAnalysisVO.AiSignal> aiSignals = scores.aiSignals().stream()
                .map(signal -> new ChapterAnalysisVO.AiSignal(signal.name(), signal.detail()))
                .toList();

        AnalysisDraft draft = AnalysisDraft.builder()
                .chapter(chapter)
                .novelTitle(novelTitle)
                .model(preferModel(scoreJson.model(), factJson.model()))
                .latencyMs(System.currentTimeMillis() - startedAt)
                .targetWords(targetWords)
                .minWords(minWords)
                .maxWords(maxWords)
                .wordPass(wordPass)
                .wordLabel(wordLabel)
                .wordComment(hasText(scores.lengthComment()) ? scores.lengthComment() : defaultLengthComment(wordCount, targetWords))
                .outlineScore(clampScore(scores.outlineScore()))
                .outlineVerdict(text(scores.outlineVerdict(), "—"))
                .outlineHits(scores.outlineHits())
                .outlineMisses(scores.outlineMisses())
                .outlineSuggestions(scores.outlineSuggestions())
                .aiScore(aiScore)
                .aiVerdict(text(scores.aiVerdict(), aiVerdict(aiScore)))
                .aiSignals(aiSignals)
                .aiExamples(scores.aiExamples())
                .localSignals(localSignals)
                .warnings(warnings.size() > MAX_WARNINGS ? warnings.subList(0, MAX_WARNINGS) : warnings)
                .facts(facts)
                .build();

        log.info("[analysis] chapterId={} words={}/{} outline={} ai={} newChars={} changes={} events={}",
                chapterId, wordCount, targetWords, draft.getOutlineScore(), draft.getAiScore(),
                facts.newCharacters().size(),
                facts.characterChanges().size() + facts.itemChanges().size() + facts.factionChanges().size(),
                facts.eventChanges().size());
        return applier.persist(draft);
    }

    /* ------------------------------------------------------------------ */
    /* 查询                                                               */
    /* ------------------------------------------------------------------ */

    public List<ChapterAnalysisSummaryVO> listByChapter(Long chapterId) {
        assetService.getChapter(chapterId);
        return analysisMapper.selectList(Wrappers.<ChapterAnalysisEntity>lambdaQuery()
                        .eq(ChapterAnalysisEntity::getChapterId, chapterId)
                        .orderByDesc(ChapterAnalysisEntity::getCreatedAt)
                        .last("LIMIT " + ANALYSIS_HISTORY_LIMIT))
                .stream()
                .map(row -> new ChapterAnalysisSummaryVO(row.getId(), row.getChapterId(), row.getChapterNo(),
                        row.getChapterTitle(), row.getWordCount(), row.getTargetWords(),
                        row.getWordPass() != null && row.getWordPass() == 1,
                        row.getOutlineScore(), row.getAiScore(), row.getModel(), row.getCreatedAt()))
                .toList();
    }

    /**
     * 分析面板列表：全书章节 + 各自最近一次分析的结果。
     *
     * <p>按顺序分析：只有前面的章节都已有分析记录，当前章才可分析。
     * 正文为空的章节既不能分析，也会把后面的章节一起挡住。</p>
     */
    public List<ChapterAnalysisOverviewVO> overview(Long novelId) {
        if (novelMapper.selectById(novelId) == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        List<Map<String, Object>> rows = chapterMapper.selectMaps(Wrappers.<ChapterEntity>query()
                .select("id", "volume_no", "volume_title", "chapter_no", "title", "word_count",
                        "CASE WHEN content IS NULL OR btrim(content) = '' THEN 1 ELSE 0 END AS content_blank")
                .eq("novel_id", novelId)
                .orderByAsc("sort_no")
                .orderByAsc("chapter_no"));

        List<ChapterAnalysisEntity> analyses = analysisMapper.selectList(
                Wrappers.<ChapterAnalysisEntity>lambdaQuery()
                        .eq(ChapterAnalysisEntity::getNovelId, novelId)
                        .orderByAsc(ChapterAnalysisEntity::getCreatedAt));
        Map<Long, ChapterAnalysisEntity> latest = new HashMap<>();
        Map<Long, Integer> counts = new HashMap<>();
        for (ChapterAnalysisEntity row : analyses) {
            counts.merge(row.getChapterId(), 1, Integer::sum);
            // 升序遍历，后写覆盖前面的，留下的就是最近一次
            latest.put(row.getChapterId(), row);
        }

        int defaultTarget = defaultTarget(rows);
        int minWords = (int) Math.floor(defaultTarget * (1 - PASS_RATIO));
        int maxWords = (int) Math.ceil(defaultTarget * (1 + PASS_RATIO));

        List<ChapterAnalysisOverviewVO> result = new ArrayList<>(rows.size());
        boolean priorDone = true;
        String blocker = "";
        for (Map<String, Object> row : rows) {
            Long chapterId = asLong(row.get("id"));
            Integer volumeNo = asInt(row.get("volume_no"), 1);
            Integer chapterNo = asInt(row.get("chapter_no"), 0);
            String title = row.get("title") == null ? "" : String.valueOf(row.get("title"));
            String volumeTitle = row.get("volume_title") == null ? null : String.valueOf(row.get("volume_title"));
            Integer wordCount = asInt(row.get("word_count"), 0);
            boolean blank = asInt(row.get("content_blank"), 0) == 1;
            ChapterAnalysisEntity analysis = chapterId == null ? null : latest.get(chapterId);
            String label = chapterLabel(chapterNo, title);

            String status;
            String statusLabel;
            boolean analyzable;
            String blockReason = "";
            if (analysis != null) {
                status = "ANALYZED";
                statusLabel = "已分析";
                analyzable = true;
            } else if (blank) {
                status = "EMPTY";
                statusLabel = "正文为空";
                analyzable = false;
                blockReason = "本章还没有正文，无法分析";
            } else if (priorDone) {
                status = "READY";
                statusLabel = "待分析";
                analyzable = true;
            } else {
                status = "LOCKED";
                statusLabel = "未就绪";
                analyzable = false;
                blockReason = "需要先分析「" + blocker + "」";
            }

            Integer targetWords = analysis != null && analysis.getTargetWords() != null
                    ? analysis.getTargetWords() : defaultTarget;
            int countedWords = analysis != null && analysis.getWordCount() != null
                    ? analysis.getWordCount() : wordCount;
            boolean wordPass = !blank && countedWords >= minWords && countedWords <= maxWords;

            result.add(new ChapterAnalysisOverviewVO(chapterId, volumeNo, volumeTitle, chapterNo, title,
                    countedWords, status, statusLabel, analyzable, blockReason,
                    analysis == null ? null : analysis.getId(),
                    analysis == null ? null : analysis.getOutlineScore(),
                    analysis == null ? null : analysis.getAiScore(),
                    analysis == null ? wordPass
                            : analysis.getWordPass() != null && analysis.getWordPass() == 1,
                    wordLabel(countedWords, targetWords), targetWords,
                    counts.getOrDefault(chapterId, 0),
                    analysis == null ? null : analysis.getCreatedAt()));

            if (analysis == null) {
                priorDone = false;
                if (blocker.isEmpty()) {
                    blocker = label;
                }
            }
        }
        return result;
    }

    private int defaultTarget(List<Map<String, Object>> rows) {
        double average = rows.stream()
                .map(row -> asInt(row.get("word_count"), 0))
                .filter(count -> count > 0)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(DEFAULT_TARGET_WORDS);
        int rounded = (int) (Math.round(average / 100.0D) * 100);
        return Math.min(MAX_TARGET_WORDS, Math.max(MIN_TARGET_WORDS, rounded));
    }

    private String chapterLabel(int chapterNo, String title) {
        String name = hasText(title) ? title : "未命名";
        return chapterNo <= 0 ? name : "第 " + chapterNo + " 章 " + name;
    }

    private Long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && hasText(text)) {
            try {
                return (int) Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    /** 本章之前的所有章节都必须已有分析记录 */
    private void ensurePriorAnalyzed(Long novelId, List<ChapterVO> chapters, ChapterEntity chapter) {
        int currentKey = sortKey(chapter.getVolumeNo(), chapter.getChapterNo());
        List<ChapterVO> priors = chapters.stream()
                .filter(item -> item.id() != null
                        && sortKey(item.volumeNo(), item.chapterNo()) < currentKey)
                .sorted(Comparator.comparingInt(item -> sortKey(item.volumeNo(), item.chapterNo())))
                .toList();
        if (priors.isEmpty()) {
            return;
        }
        Set<Long> analyzed = new HashSet<>(analysisMapper.selectList(
                        Wrappers.<ChapterAnalysisEntity>lambdaQuery()
                                .select(ChapterAnalysisEntity::getChapterId)
                                .eq(ChapterAnalysisEntity::getNovelId, novelId))
                .stream()
                .map(ChapterAnalysisEntity::getChapterId)
                .toList());
        for (ChapterVO prior : priors) {
            if (!analyzed.contains(prior.id())) {
                throw BizException.of(ErrorCode.CHAPTER_ORDER_VIOLATION,
                        "请先分析「" + chapterLabel(prior.chapterNo() == null ? 0 : prior.chapterNo(),
                                prior.title()) + "」");
            }
        }
    }

    private ChapterVO previousOf(List<ChapterVO> chapters, ChapterEntity chapter) {
        int currentKey = sortKey(chapter.getVolumeNo(), chapter.getChapterNo());
        return chapters.stream()
                .filter(item -> sortKey(item.volumeNo(), item.chapterNo()) < currentKey)
                .max(Comparator.comparingInt(item -> sortKey(item.volumeNo(), item.chapterNo())))
                .orElse(null);
    }

    public ChapterAnalysisVO get(Long analysisId) {
        ChapterAnalysisEntity row = analysisMapper.selectById(analysisId);
        if (row == null) {
            throw BizException.of(ErrorCode.ANALYSIS_NOT_FOUND);
        }
        if (hasText(row.getResultJson())) {
            try {
                return objectMapper.readValue(row.getResultJson(), ChapterAnalysisVO.class);
            } catch (Exception ex) {
                log.warn("[analysis] parse report failed id={}: {}", analysisId, ex.getMessage());
            }
        }
        return new ChapterAnalysisVO(row.getId(), row.getChapterId(), row.getChapterNo(), row.getChapterTitle(),
                row.getWordCount(), row.getTargetWords(), 0, 0,
                row.getWordPass() != null && row.getWordPass() == 1, "", "",
                row.getOutlineScore(), "", List.of(), List.of(), List.of(),
                row.getAiScore(), aiVerdict(row.getAiScore()), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                row.getModel(), row.getLatencyMs(), row.getCreatedAt());
    }

    public List<ChangeHistoryVO> listHistories(Long novelId, String entityType, Long entityId) {
        if (novelMapper.selectById(novelId) == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        String type = entityType == null ? null : entityType.trim().toUpperCase(Locale.ROOT);
        if (type != null && !type.isEmpty() && !NovelLabels.ENTITY_TYPE.containsKey(type)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "不支持的履历类型");
        }
        return changeHistoryMapper.selectList(Wrappers.<ChangeHistoryEntity>lambdaQuery()
                        .eq(ChangeHistoryEntity::getNovelId, novelId)
                        .eq(type != null && !type.isEmpty(), ChangeHistoryEntity::getEntityType, type)
                        .eq(entityId != null, ChangeHistoryEntity::getEntityId, entityId)
                        .orderByDesc(ChangeHistoryEntity::getChapterNo)
                        .orderByDesc(ChangeHistoryEntity::getCreatedAt)
                        .last("LIMIT 200"))
                .stream()
                .map(row -> new ChangeHistoryVO(row.getId(), row.getChapterId(), row.getChapterNo(),
                        row.getChapterTitle(), row.getEntityType(),
                        NovelLabels.entityTypeLabel(row.getEntityType()), row.getEntityId(), row.getEntityName(),
                        row.getFieldKey(), row.getFieldLabel(), row.getBeforeState(), row.getAfterState(),
                        row.getReason(), row.getEvidence(), row.getCreatedAt()))
                .toList();
    }

    /* ------------------------------------------------------------------ */
    /* AI 调用与解析                                                       */
    /* ------------------------------------------------------------------ */

    private AiEndpoint endpoint(AiScene scene, double temperature, int maxTokens) {
        AiEndpoint base = endpointResolver.resolve(scene);
        return new AiEndpoint(base.baseUrl(), base.apiKey(), base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS), base.extraHeaders(), temperature, maxTokens);
    }

    private record AiJson(JsonNode node, String model, long latencyMs) {
    }

    private AiJson callJson(AiScene scene, AiEndpoint endpoint, String system, String user, String tag) {
        ChatResult reply = chat(scene, endpoint, system, user, tag);
        JsonNode node = tryParse(reply.content());
        if (node == null) {
            log.warn("[analysis] {} json parse failed, retrying once: {}", tag, brief(reply.content()));
            ChatResult retry = chat(scene, endpoint, system, user + RETRY_SUFFIX, tag + "-retry");
            node = tryParse(retry.content());
            if (node == null) {
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, "AI 返回的内容不是合法 JSON，请重试");
            }
            return new AiJson(node, retry.model(), retry.latencyMs());
        }
        return new AiJson(node, reply.model(), reply.latencyMs());
    }

    private ChatResult chat(AiScene scene, AiEndpoint endpoint, String system, String user, String tag) {
        try {
            return aiUsageService.chat(scene, endpoint, List.of(
                    ChatMessage.system(system),
                    ChatMessage.user(user)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("[analysis] {} chat failed: {}", tag, ex.toString());
            throw BizException.of(ErrorCode.AI_CALL_FAILED,
                    hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
        }
    }

    private JsonNode tryParse(String raw) {
        if (!hasText(raw)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(LenientJson.repair(raw));
            return node != null && node.isObject() ? node : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private AnalysisFacts parseFacts(JsonNode root) {
        List<AnalysisFacts.NewCharacter> newCharacters = new ArrayList<>();
        for (JsonNode node : array(root, "newCharacters")) {
            if (newCharacters.size() >= MAX_NEW_CHARACTERS) {
                break;
            }
            String name = text(node, "name");
            if (!hasText(name)) {
                continue;
            }
            newCharacters.add(new AnalysisFacts.NewCharacter(name,
                    text(node, "identity"), text(node, "rolePos"), text(node, "gender"),
                    text(node, "ageDesc"), text(node, "appearance"), text(node, "personality"),
                    text(node, "motivation"), text(node, "reason")));
        }
        return new AnalysisFacts(clipText(text(root, "factSummary"), MAX_FACT_SUMMARY_CHARS),
                newCharacters,
                parseChanges(root, "characterChanges"),
                parseChanges(root, "itemChanges"),
                parseChanges(root, "factionChanges"),
                parseEventChanges(root),
                parseChanges(root, "attributeChanges"),
                parseChanges(root, "inventoryChanges"),
                parseCharacterStates(root),
                parseForeshadowPlanted(root),
                parseForeshadowPaid(root));
    }

    /** 角色状态变化：只收变了的人，姓名缺失或四项全空的整条丢弃。 */
    private List<AnalysisFacts.CharacterState> parseCharacterStates(JsonNode root) {
        List<AnalysisFacts.CharacterState> states = new ArrayList<>();
        for (JsonNode node : array(root, "characterStates")) {
            if (states.size() >= MAX_CHARACTER_STATES) {
                break;
            }
            String name = text(node, "name");
            if (!hasText(name)) {
                continue;
            }
            String location = clipText(text(node, "location"), 100);
            String condition = clipText(text(node, "condition"), 200);
            String mood = clipText(text(node, "mood"), 60);
            List<String> added = stringList(node, "knowsAdded", MAX_KNOWS_PER_SIDE);
            List<String> hidden = stringList(node, "knowsHidden", MAX_KNOWS_PER_SIDE);
            if (!hasText(location) && !hasText(condition) && !hasText(mood)
                    && added.isEmpty() && hidden.isEmpty()) {
                continue;
            }
            states.add(new AnalysisFacts.CharacterState(name, location, condition, mood, added, hidden));
        }
        return states;
    }

    private List<AnalysisFacts.ForeshadowPlanted> parseForeshadowPlanted(JsonNode root) {
        List<AnalysisFacts.ForeshadowPlanted> planted = new ArrayList<>();
        for (JsonNode node : array(root, "foreshadowPlanted")) {
            if (planted.size() >= MAX_FORESHADOW_PER_CHAPTER) {
                break;
            }
            String title = clipText(text(node, "title"), 60);
            if (!hasText(title)) {
                continue;
            }
            Integer planned = null;
            JsonNode chapterNode = node.get("plannedPayoffChapter");
            if (chapterNode != null && chapterNode.isNumber() && chapterNode.asInt() > 0) {
                planned = chapterNode.asInt();
            } else if (chapterNode != null && chapterNode.isTextual()) {
                String raw = chapterNode.asText().trim();
                if (raw.matches("\\d+") && Integer.parseInt(raw) > 0) {
                    planned = Integer.parseInt(raw);
                }
            }
            planted.add(new AnalysisFacts.ForeshadowPlanted(title,
                    clipText(text(node, "content"), 300), planned));
        }
        return planted;
    }

    private List<AnalysisFacts.ForeshadowPaid> parseForeshadowPaid(JsonNode root) {
        List<AnalysisFacts.ForeshadowPaid> paid = new ArrayList<>();
        for (JsonNode node : array(root, "foreshadowPaid")) {
            if (paid.size() >= MAX_FORESHADOW_PER_CHAPTER) {
                break;
            }
            String title = clipText(text(node, "title"), 60);
            if (!hasText(title)) {
                continue;
            }
            paid.add(new AnalysisFacts.ForeshadowPaid(title, clipText(text(node, "evidence"), 200)));
        }
        return paid;
    }

    /** 从节点的 JSON 数组里取字符串列表（也接受单个字符串），去空去重后截断。 */
    private List<String> stringList(JsonNode node, String field, int max) {
        if (node == null) {
            return List.of();
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (value.isTextual()) {
            String single = value.asText().trim();
            if (!single.isEmpty()) {
                result.add(clipText(single, 120));
            }
            return result;
        }
        if (!value.isArray()) {
            return List.of();
        }
        for (JsonNode item : value) {
            if (result.size() >= max) {
                break;
            }
            String text = item == null ? null : (item.isTextual() ? item.asText() : item.toString());
            if (text == null || text.isBlank()) {
                continue;
            }
            String clipped = clipText(text.trim(), 120);
            if (!result.contains(clipped)) {
                result.add(clipped);
            }
        }
        return result;
    }

    private String clipText(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private List<AnalysisFacts.Change> parseChanges(JsonNode root, String field) {
        List<AnalysisFacts.Change> changes = new ArrayList<>();
        for (JsonNode node : array(root, field)) {
            if (changes.size() >= MAX_CHANGES_PER_TYPE) {
                break;
            }
            String name = text(node, "name");
            String key = text(node, "field");
            if (!hasText(name) || !hasText(key)) {
                continue;
            }
            changes.add(new AnalysisFacts.Change(name, key, text(node, "after"),
                    text(node, "reason"), text(node, "evidence")));
        }
        return changes;
    }

    private List<AnalysisFacts.EventChange> parseEventChanges(JsonNode root) {
        List<AnalysisFacts.EventChange> changes = new ArrayList<>();
        for (JsonNode node : array(root, "eventChanges")) {
            if (changes.size() >= MAX_EVENT_CHANGES) {
                break;
            }
            String title = text(node, "title");
            String status = text(node, "status");
            if (!hasText(title) || !hasText(status)) {
                continue;
            }
            changes.add(new AnalysisFacts.EventChange(title, status,
                    text(node, "reason"), text(node, "evidence")));
        }
        return changes;
    }

    private AnalysisScores parseScores(JsonNode root) {
        JsonNode outline = root.path("outline");
        JsonNode ai = root.path("aiFlavor");
        List<AnalysisScores.Signal> signals = new ArrayList<>();
        for (JsonNode node : array(ai, "signals")) {
            if (signals.size() >= 6) {
                break;
            }
            String name = text(node, "name");
            if (hasText(name)) {
                signals.add(new AnalysisScores.Signal(name, text(node, "detail")));
            }
        }
        return new AnalysisScores(
                outline.path("score").asInt(0),
                text(outline, "verdict"),
                stringList(outline, "hits"),
                stringList(outline, "misses"),
                stringList(outline, "suggestions"),
                ai.path("score").asInt(0),
                text(ai, "verdict"),
                signals,
                stringList(ai, "examples"),
                text(root, "lengthComment"));
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private List<JsonNode> array(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (!node.isArray()) {
            return List.of();
        }
        List<JsonNode> list = new ArrayList<>();
        node.forEach(list::add);
        return list;
    }

    private List<String> stringList(JsonNode root, String field) {
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode node : array(root, field)) {
            String value = node.isTextual() ? node.asText().trim() : "";
            if (!value.isEmpty()) {
                values.add(value.length() > 300 ? value.substring(0, 300) : value);
            }
            if (values.size() >= MAX_LIST_ITEMS) {
                break;
            }
        }
        return new ArrayList<>(values);
    }

    private String text(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return "";
        }
        String value = node.isTextual() ? node.asText() : node.toString();
        return value == null ? "" : value.trim();
    }

    private String text(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private int resolveTarget(ChapterAnalysisRequest request, List<ChapterVO> chapters) {
        if (request != null && request.getTargetWords() != null) {
            return Math.min(MAX_TARGET_WORDS, Math.max(MIN_TARGET_WORDS, request.getTargetWords()));
        }
        List<Integer> counts = chapters.stream()
                .map(ChapterVO::wordCount)
                .filter(count -> count != null && count > 0)
                .toList();
        if (counts.isEmpty()) {
            return DEFAULT_TARGET_WORDS;
        }
        double average = counts.stream().mapToInt(Integer::intValue).average().orElse(DEFAULT_TARGET_WORDS);
        int rounded = (int) (Math.round(average / 100.0D) * 100);
        return Math.min(MAX_TARGET_WORDS, Math.max(MIN_TARGET_WORDS, rounded));
    }

    private String wordLabel(int wordCount, int targetWords) {
        if (targetWords <= 0) {
            return "—";
        }
        double ratio = (double) wordCount / targetWords;
        if (Math.abs(ratio - 1) <= PASS_RATIO) {
            return "符合";
        }
        if (ratio > 1) {
            return ratio <= 1 + HEAVY_RATIO ? "偏多" : "明显偏多";
        }
        return ratio >= 1 - HEAVY_RATIO ? "偏少" : "明显偏少";
    }

    private String defaultLengthComment(int wordCount, int targetWords) {
        double ratio = targetWords <= 0 ? 1 : (double) wordCount / targetWords;
        if (Math.abs(ratio - 1) <= PASS_RATIO) {
            return "字数落在目标区间内，节奏与篇幅匹配。";
        }
        return ratio > 1
                ? "比目标多出 " + Math.round((ratio - 1) * 100) + "%，注意有没有可以删的过场。"
                : "比目标少 " + Math.round((1 - ratio) * 100) + "%，关键转折可能铺不开。";
    }

    private String aiVerdict(Integer score) {
        int value = score == null ? 0 : score;
        if (value <= 20) {
            return "高度疑似 AI";
        }
        if (value <= 40) {
            return "AI 痕迹明显";
        }
        if (value <= 60) {
            return "人机混杂";
        }
        if (value <= 80) {
            return "较为自然";
        }
        return "接近人写";
    }

    private int clampScore(int score) {
        return Math.max(0, Math.min(100, score));
    }

    private int sortKey(Integer volumeNo, Integer chapterNo) {
        return (volumeNo == null ? 1 : volumeNo) * 1000 + (chapterNo == null ? 0 : chapterNo);
    }

    private String preferModel(String first, String second) {
        return hasText(first) ? first : second;
    }

    private String brief(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim().replaceAll("\\s+", " ");
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200) + "…";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
