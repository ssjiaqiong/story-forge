package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.PageQuery;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.context.UserContext;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WordCounts;
import com.storyforge.infra.ai.AiEndpoint;
import com.storyforge.infra.ai.ChatMessage;
import com.storyforge.infra.ai.ChatResult;
import com.storyforge.infra.ai.LenientJson;
import com.storyforge.infra.text.NovelChapterSplitter;
import com.storyforge.infra.text.NovelTextParser;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.ai.service.AiSceneEndpointResolver;
import com.storyforge.modules.ai.service.AiUsageService;
import com.storyforge.modules.bookshelf.dto.ImportSummaryVO;
import com.storyforge.modules.bookshelf.dto.ImportTaskVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterRelationEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import com.storyforge.modules.bookshelf.entity.NovelImportEntity;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterRelationMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMemberMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import com.storyforge.modules.bookshelf.mapper.MasterOutlineMapper;
import com.storyforge.modules.bookshelf.mapper.NovelImportMapper;
import com.storyforge.modules.bookshelf.mapper.SettingDocMapper;
import com.storyforge.modules.novel.dto.NovelDraft;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import com.storyforge.modules.novel.service.NovelService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 整本 TXT 导入：把「只剩正文」的成稿变成一本可继续写作的书。
 *
 * <p>整个流水线分两段，边界很清楚：</p>
 * <ol>
 *   <li><b>入库（本地，快）</b>：解码 → 清洗 → 切章 → 建书 → 章节正文逐条落库。
 *       这一段不调 AI，失败就是文件问题；成功了这本书立刻能打开、能编辑、能续写。</li>
 *   <li><b>逆推（AI，慢）</b>：分段通读全书 → 归纳设定 → 人物/势力/关系 → 物品 → 总纲 → 逐章大纲。
 *       这一段是「把作者原本心里那份档案补出来」，失败不影响已入库的正文，可以只重跑这一段。</li>
 * </ol>
 *
 * <p>与拆书的区别：拆书读完就丢原文、产出一份改编大纲；导入一个字都不丢，产出的是这本书自己的档案。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelImportService {

    /** 少于这个汉字数不认为是一本书。 */
    private static final int MIN_CHARS = 1000;
    /** 单本上限：500 万汉字，超过建议拆分（AI 通读也只抽样，再大意义不大）。 */
    private static final int MAX_CHARS = 5_000_000;
    /** 进度总分步数（含入库与收尾）。 */
    private static final int STEP_TOTAL = 8;
    /** 逐章大纲一批处理多少章。 */
    private static final int OUTLINE_BATCH = 12;
    /** 逐章大纲输入里每章正文取多少字。 */
    private static final int EXCERPT_CHARS = 200;
    /** AI 单次调用超时。 */
    private static final int TIMEOUT_MS = 600_000;

    private static final String CATEGORY_WORLDVIEW = "WORLDVIEW";
    private static final String CATEGORY_BACKGROUND = "BACKGROUND";
    private static final String CATEGORY_CORE_CONFLICT = "CORE_CONFLICT";
    private static final String CATEGORY_HIGHLIGHT = "HIGHLIGHT";
    private static final String CATEGORY_STYLE = "STYLE";

    private final NovelImportMapper importMapper;
    private final NovelService novelService;
    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final VolumeService volumeService;
    private final SettingDocMapper settingDocMapper;
    private final CharacterMapper characterMapper;
    private final CharacterRelationMapper characterRelationMapper;
    private final FactionMapper factionMapper;
    private final FactionMemberMapper factionMemberMapper;
    private final ItemMapper itemMapper;
    private final MasterOutlineMapper masterOutlineMapper;
    private final AiSceneEndpointResolver endpointResolver;
    private final AiUsageService aiUsageService;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    private final AtomicInteger threadSeq = new AtomicInteger();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "novel-import-" + threadSeq.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    /** 服务重启后把残留的 RUNNING 任务标记为失败，避免前端一直转圈。 */
    @EventListener(ApplicationReadyEvent.class)
    public void markInterrupted() {
        try {
            int rows = importMapper.update(null, Wrappers.<NovelImportEntity>lambdaUpdate()
                    .eq(NovelImportEntity::getStatus, NovelImportEntity.STATUS_RUNNING)
                    .set(NovelImportEntity::getStatus, NovelImportEntity.STATUS_FAILED)
                    .set(NovelImportEntity::getStepLabel, "导入中断")
                    .set(NovelImportEntity::getErrorMessage, "服务重启导致导入中断；正文已入库，可点「继续分析」接着跑")
                    .set(NovelImportEntity::getUpdatedAt, LocalDateTime.now()));
            if (rows > 0) {
                log.info("[import] marked {} interrupted task(s) as FAILED", rows);
            }
        } catch (RuntimeException ex) {
            log.warn("[import] cleanup interrupted tasks failed: {}", ex.getMessage());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 对外入口                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 上传整本 TXT 并开始导入。
     *
     * @param file     纯文本文件（txt / md）
     * @param title    书名，可空（空则用 AI 判定的书名，建书时先用文件名兜底）
     * @param penName  笔名，可空
     */
    public ImportTaskVO start(MultipartFile file, String title, String penName) {
        Long userId = UserContextHolder.requireUserId();
        UserContext context = UserContextHolder.require();
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请先选择要导入的 TXT 文件");
        }
        String fileName = trimToNull(file.getOriginalFilename());
        if (fileName != null && !isTextFile(fileName)) {
            throw BizException.of(ErrorCode.IMPORT_FILE_INVALID);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "读取上传文件失败");
        }
        String raw = NovelTextParser.decode(bytes);
        int chars = WordCounts.countChinese(NovelTextParser.clean(raw));
        if (chars < MIN_CHARS) {
            throw BizException.of(ErrorCode.IMPORT_TEXT_TOO_SHORT);
        }
        if (chars > MAX_CHARS) {
            throw BizException.of(ErrorCode.IMPORT_TEXT_TOO_LONG);
        }

        Long running = importMapper.selectCount(Wrappers.<NovelImportEntity>lambdaQuery()
                .eq(NovelImportEntity::getUserId, userId)
                .eq(NovelImportEntity::getStatus, NovelImportEntity.STATUS_RUNNING));
        if (running != null && running > 0) {
            throw BizException.of(ErrorCode.IMPORT_RUNNING);
        }

        NovelChapterSplitter.Result split;
        try {
            split = NovelChapterSplitter.split(raw);
        } catch (NovelChapterSplitter.TooManyChaptersException ex) {
            throw BizException.of(ErrorCode.PARAM_INVALID, ex.getMessage());
        }
        if (split.chapters().isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "没有从文件里识别到任何正文，请确认是小说文本");
        }

        // 端点解析放在调用线程：没配 AI 立刻报 40002，不必等章节入库完才失败
        endpointResolver.resolve(AiScene.IMPORT_ANALYZE);

        String wantedTitle = trimToNull(title);
        NovelImportEntity entity = new NovelImportEntity();
        entity.setUserId(userId);
        entity.setSourceName(truncate(fileName == null ? "未命名文本" : fileName, 200));
        entity.setTitle(truncate(wantedTitle == null ? guessTitle(fileName, split) : wantedTitle, 200));
        entity.setPenName(truncate(trimToNull(penName), 64));
        entity.setSourceChars(split.chars());
        entity.setChapterCount(split.chapters().size());
        entity.setVolumeCount(split.volumeCount());
        entity.setImportedChapters(0);
        entity.setStatus(NovelImportEntity.STATUS_RUNNING);
        entity.setPercent(1);
        entity.setStepNo(0);
        entity.setStepTotal(STEP_TOTAL);
        entity.setStepLabel("正在准备");
        entity.setDeleted(0);
        entity.setVersion(0);
        importMapper.insert(entity);

        String taskId = String.valueOf(entity.getId());
        executor.submit(() -> run(entity.getId(), context, raw));
        log.info("[import] started taskId={} userId={} file={} chars={} chapters={}",
                taskId, userId, entity.getSourceName(), split.chars(), split.chapters().size());
        return toVO(entity);
    }

    /**
     * 继续跑 AI 分析（正文已入库、上次分析失败的场景）。
     *
     * <p>不重建书、不重插章节，只把逆推流水线再走一遍。</p>
     */
    public ImportTaskVO retry(String taskId) {
        NovelImportEntity entity = require(taskId);
        Long userId = UserContextHolder.requireUserId();
        if (!userId.equals(entity.getUserId())) {
            throw BizException.of(ErrorCode.IMPORT_NOT_FOUND);
        }
        if (NovelImportEntity.STATUS_RUNNING.equals(entity.getStatus())) {
            throw BizException.of(ErrorCode.IMPORT_RUNNING);
        }
        if (entity.getNovelId() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "这本书还没入库，请重新上传文件");
        }
        endpointResolver.resolve(AiScene.IMPORT_ANALYZE);
        importMapper.update(null, Wrappers.<NovelImportEntity>lambdaUpdate()
                .eq(NovelImportEntity::getId, entity.getId())
                .set(NovelImportEntity::getStatus, NovelImportEntity.STATUS_RUNNING)
                .set(NovelImportEntity::getPercent, 20)
                .set(NovelImportEntity::getStepNo, 1)
                .set(NovelImportEntity::getStepLabel, "正在重新分析")
                .set(NovelImportEntity::getErrorMessage, null)
                .set(NovelImportEntity::getUpdatedAt, LocalDateTime.now()));
        UserContext context = UserContextHolder.require();
        executor.submit(() -> run(entity.getId(), context, null));
        log.info("[import] retry taskId={} novelId={} userId={}", taskId, entity.getNovelId(), userId);
        return status(taskId);
    }

    /** 查询任务状态。 */
    public ImportTaskVO status(String taskId) {
        return toVO(require(taskId));
    }

    /** 导入记录列表。 */
    public PageResult<ImportSummaryVO> list(PageQuery query) {
        PageQuery safe = query == null ? new PageQuery() : query;
        Long userId = UserContextHolder.requireUserId();
        IPage<NovelImportEntity> page = importMapper.selectPage(
                new Page<>(safe.normalizedPage(), safe.normalizedSize()),
                Wrappers.<NovelImportEntity>lambdaQuery()
                        .eq(NovelImportEntity::getUserId, userId)
                        .orderByDesc(NovelImportEntity::getCreatedAt));
        List<ImportSummaryVO> records = page.getRecords().stream().map(this::toSummary).toList();
        return PageResult.of(records, page.getTotal(), safe.normalizedPage(), safe.normalizedSize());
    }

    /** 删除导入记录（已生成的书保留）。 */
    public void delete(String taskId) {
        NovelImportEntity entity = require(taskId);
        importMapper.deleteById(entity.getId());
        log.info("[import] deleted taskId={} userId={}", taskId, entity.getUserId());
    }

    /* ------------------------------------------------------------------ */
    /* 流水线                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * @param raw 上传原文；为 null 表示「继续分析」——全文从已入库的章节拼回来
     */
    private void run(Long id, UserContext context, String raw) {
        UserContextHolder.set(context);
        long startedAt = System.currentTimeMillis();
        try {
            NovelImportEntity entity = importMapper.selectById(id);
            if (entity == null) {
                return;
            }
            Long novelId = entity.getNovelId();
            if (novelId == null) {
                progress(id, 1, "正在把原文按章入库", 4, null);
                novelId = persistBook(entity, raw);
                raw = null;
            } else {
                progress(id, 1, "原文已在库，直接开始分析", 20, null);
            }

            List<ChapterRow> chapters = loadChapters(novelId);
            if (chapters.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "这本书里没有章节正文，无法分析");
            }

            AiEndpoint base = endpointResolver.resolve(AiScene.IMPORT_ANALYZE);
            String fullText = rebuildText(chapters);

            // 2. 分段通读
            NovelTextParser.Digest digest = NovelTextParser.parse(fullText);
            List<NovelTextParser.Segment> segments = digest.segments();
            List<JsonNode> notes = new ArrayList<>();
            for (int i = 0; i < segments.size(); i++) {
                NovelTextParser.Segment segment = segments.get(i);
                int percent = 20 + (int) Math.round(24.0 * i / Math.max(1, segments.size()));
                notes.add(call(base, NovelImportPromptBuilder.SYSTEM,
                        NovelImportPromptBuilder.segmentPrompt(segment, chapters.size(), digest.chars()),
                        1800, 0.3D, id, 2,
                        "正在通读第 " + segment.index() + "/" + segment.total() + " 段", percent));
            }
            String notesText = renderNotes(notes);

            // 3. 设定
            JsonNode setting = call(base, NovelImportPromptBuilder.SYSTEM,
                    NovelImportPromptBuilder.settingPrompt(notesText),
                    4000, 0.5D, id, 3, "正在还原世界观与背景", 48);
            saveSettings(novelId, entity.getUserId(), setting);
            applyNovelMeta(novelId, setting);

            // 4. 人物 / 势力 / 关系
            JsonNode cast = call(base, NovelImportPromptBuilder.SYSTEM,
                    NovelImportPromptBuilder.castPrompt(notesText, renderSetting(setting), chapters.size()),
                    6000, 0.5D, id, 4, "正在建立人物档案", 60);
            Map<String, Long> characterIds = saveCast(novelId, entity.getUserId(), cast);

            // 5. 物品
            JsonNode items = call(base, NovelImportPromptBuilder.SYSTEM,
                    NovelImportPromptBuilder.itemPrompt(notesText, renderSetting(setting)),
                    3000, 0.4D, id, 5, "正在整理物品", 70);
            int itemCount = saveItems(novelId, entity.getUserId(), items);

            // 6. 总纲
            JsonNode master = call(base, NovelImportPromptBuilder.SYSTEM,
                    NovelImportPromptBuilder.masterOutlinePrompt(
                            renderSetting(setting), renderCast(cast), notesText, chapters.size()),
                    4000, 0.5D, id, 6, "正在梳理总纲", 78);
            Long masterId = saveMasterOutline(novelId, entity.getUserId(), master, chapters.size());

            // 7. 逐章大纲
            int outlined = fillChapterOutlines(base, novelId, chapters, renderMaster(master), masterId, id);

            // 8. 收尾
            progress(id, 8, "正在整理结果", 98, null);
            ImportTaskVO.Summary summary = new ImportTaskVO.Summary();
            summary.setCharacters(characterIds.size());
            summary.setRelations(cast.path("relations").isArray() ? cast.path("relations").size() : 0);
            summary.setFactions(cast.path("factions").isArray() ? cast.path("factions").size() : 0);
            summary.setItems(itemCount);
            summary.setSettings(countSettings(novelId));
            summary.setMasterOutlines(masterId == null ? 0 : 1);
            summary.setChapterOutlines(outlined);
            summary.setReadSegments(segments.size());
            summary.setSampled(digest.sampled());
            summary.setModel(base.model());
            summary.setLatencyMs(System.currentTimeMillis() - startedAt);
            List<String> noteList = new ArrayList<>();
            noteList.add("原文 " + chapters.size() + " 章、" + digest.chars() + " 字");
            noteList.add(digest.sampled()
                    ? "通读 " + segments.size() + " 段（长书按头/中/尾抽样）"
                    : "通读 " + segments.size() + " 段（覆盖全书）");
            summary.setNotes(noteList);

            finish(id, summary);
            log.info("[import] succeeded taskId={} novelId={} chapters={} chars={} characters={} items={} latency={}ms",
                    id, novelId, chapters.size(), digest.chars(), characterIds.size(), itemCount,
                    summary.getLatencyMs());
        } catch (BizException ex) {
            fail(id, ex.getMessage());
            log.warn("[import] failed taskId={}: {}", id, ex.getMessage());
        } catch (RuntimeException ex) {
            fail(id, hasText(ex.getMessage()) ? ex.getMessage() : "导入失败，请重试");
            log.warn("[import] failed taskId={}: {}", id, ex.toString());
        } finally {
            UserContextHolder.clear();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 入库：建书 + 章节                                                   */
    /* ------------------------------------------------------------------ */

    /** 建书 + 卷 + 章节正文，一个事务内完成；返回小说 ID。 */
    private Long persistBook(NovelImportEntity entity, String raw) {
        NovelChapterSplitter.Result split = NovelChapterSplitter.split(raw);
        Long userId = entity.getUserId();
        String title = entity.getTitle();
        String genre = guessGenre(split);
        List<String> tags = List.of();

        Long novelId = transactionTemplate.execute(status -> {
            Long created = createNovelWithUniqueTitle(userId, title, entity.getPenName(), genre, tags);
            // 卷先建齐，避免工作台大纲页卷列表为空
            Set<Integer> volumes = new LinkedHashSet<>();
            for (NovelChapterSplitter.Draft draft : split.chapters()) {
                volumes.add(draft.volumeNo());
            }
            for (Integer volumeNo : volumes) {
                String volumeTitle = split.chapters().stream()
                        .filter(item -> item.volumeNo() == volumeNo)
                        .map(NovelChapterSplitter.Draft::volumeTitle)
                        .filter(NovelImportService::hasText)
                        .findFirst().orElse(null);
                volumeService.ensureVolume(created, volumeNo, volumeTitle);
            }
            int sortNo = 1;
            int totalWords = 0;
            for (NovelChapterSplitter.Draft draft : split.chapters()) {
                ChapterEntity chapter = new ChapterEntity();
                chapter.setUserId(userId);
                chapter.setNovelId(created);
                chapter.setVolumeNo(draft.volumeNo());
                chapter.setVolumeTitle(truncate(draft.volumeTitle(), 100));
                chapter.setChapterNo(draft.chapterNo());
                chapter.setTitle(truncate(draft.title(), 200));
                chapter.setSummary(null);
                chapter.setContent(draft.content());
                chapter.setWordCount(draft.chars());
                chapter.setStatus("DRAFT");
                chapter.setSortNo(sortNo++);
                chapter.setDeleted(0);
                chapter.setVersion(0);
                chapterMapper.insert(chapter);
                totalWords += draft.chars();
            }
            novelMapper.update(null, Wrappers.<NovelEntity>lambdaUpdate()
                    .eq(NovelEntity::getId, created)
                    .set(NovelEntity::getChapterCount, split.chapters().size())
                    .set(NovelEntity::getWordCount, totalWords)
                    .set(NovelEntity::getStatus, "WRITING")
                    .set(NovelEntity::getUpdatedAt, LocalDateTime.now()));
            return created;
        });

        importMapper.update(null, Wrappers.<NovelImportEntity>lambdaUpdate()
                .eq(NovelImportEntity::getId, entity.getId())
                .set(NovelImportEntity::getNovelId, novelId)
                .set(NovelImportEntity::getImportedChapters, split.chapters().size())
                .set(NovelImportEntity::getSourceChars, split.chars())
                .set(NovelImportEntity::getChapterCount, split.chapters().size())
                .set(NovelImportEntity::getVolumeCount, split.volumeCount())
                .set(NovelImportEntity::getUpdatedAt, LocalDateTime.now()));
        log.info("[import] persisted novelId={} chapters={} chars={} volumes={}",
                novelId, split.chapters().size(), split.chars(), split.volumeCount());
        return novelId;
    }

    /** 书名在同一用户内唯一：撞名依次尝试「书名（2）」…，再不行加时间戳。 */
    private Long createNovelWithUniqueTitle(Long userId, String title, String penName,
                                            String genre, List<String> tags) {
        String base = hasText(title) ? title.trim() : "导入的作品";
        for (int attempt = 0; attempt < 10; attempt++) {
            String candidate = attempt == 0 ? base : base + "（" + (attempt + 1) + "）";
            Long exists = novelMapper.selectCount(Wrappers.<NovelEntity>lambdaQuery()
                    .eq(NovelEntity::getUserId, userId)
                    .eq(NovelEntity::getTitle, candidate));
            if (exists != null && exists > 0) {
                continue;
            }
            // source 只落在 t_novel 的既有枚举里（MANUAL / INSPIRATION / DECONSTRUCT，
            // 三库都有 CHECK 约束）；「这本书是导入来的」由 t_novel_import.novel_id 承载
            NovelVO novel = novelService.createDraft(new NovelDraft(
                    candidate, genre, tags, null, penName, "MANUAL", null));
            return novel.getId();
        }
        String fallback = base + "（" + System.currentTimeMillis() % 100000 + "）";
        NovelVO novel = novelService.createDraft(new NovelDraft(
                fallback, genre, tags, null, penName, "MANUAL", null));
        return novel.getId();
    }

    /* ------------------------------------------------------------------ */
    /* 落库：设定 / 人物 / 物品 / 总纲 / 章节大纲                           */
    /* ------------------------------------------------------------------ */

    private void saveSettings(Long novelId, Long userId, JsonNode setting) {
        int sort = 0;
        sort = insertSetting(novelId, userId, CATEGORY_WORLDVIEW, "世界观", text(setting, "worldview"), sort);
        sort = insertSetting(novelId, userId, CATEGORY_BACKGROUND, "背景设定", text(setting, "background"), sort);
        sort = insertSetting(novelId, userId, CATEGORY_CORE_CONFLICT, "核心冲突", text(setting, "coreConflict"), sort);
        sort = insertSetting(novelId, userId, CATEGORY_HIGHLIGHT, "核心看点", joinArray(setting.path("highlights")), sort);
        insertSetting(novelId, userId, CATEGORY_STYLE, "文风基调", text(setting, "style"), sort);
    }

    private int insertSetting(Long novelId, Long userId, String category, String label, String content, int sort) {
        if (!hasText(content)) {
            return sort;
        }
        SettingDocEntity entity = new SettingDocEntity();
        entity.setUserId(userId);
        entity.setNovelId(novelId);
        entity.setCategory(category);
        entity.setTitle(label);
        entity.setContentMd(content.trim());
        entity.setInjectScope("GLOBAL");
        entity.setSort(sort);
        entity.setDeleted(0);
        entity.setVersion(0);
        settingDocMapper.insert(entity);
        return sort + 1;
    }

    private int countSettings(Long novelId) {
        Long count = settingDocMapper.selectCount(Wrappers.<SettingDocEntity>lambdaQuery()
                .eq(SettingDocEntity::getNovelId, novelId));
        return count == null ? 0 : count.intValue();
    }

    /** 人物 + 势力 + 关系，返回「人名 → 角色 ID」。 */
    private Map<String, Long> saveCast(Long novelId, Long userId, JsonNode cast) {
        Map<String, Long> factionIds = new LinkedHashMap<>();
        JsonNode factions = cast.path("factions");
        if (factions.isArray()) {
            int sort = 0;
            for (JsonNode node : factions) {
                String name = text(node, "name");
                if (!hasText(name) || factionIds.containsKey(name)) {
                    continue;
                }
                FactionEntity entity = new FactionEntity();
                entity.setUserId(userId);
                entity.setNovelId(novelId);
                entity.setName(truncate(name, 64));
                entity.setCategory(truncate(text(node, "category"), 32));
                entity.setScope(truncate(text(node, "scope"), 255));
                entity.setRemark(truncate(text(node, "remark"), 2000));
                entity.setStatus("ACTIVE");
                entity.setSortNo(sort++);
                entity.setDeleted(0);
                entity.setVersion(0);
                factionMapper.insert(entity);
                factionIds.put(name, entity.getId());
            }
        }

        Map<String, Long> characterIds = new LinkedHashMap<>();
        JsonNode characters = cast.path("characters");
        if (characters.isArray()) {
            int sort = 0;
            for (JsonNode node : characters) {
                String name = text(node, "name");
                if (!hasText(name) || characterIds.containsKey(name)) {
                    continue;
                }
                CharacterEntity entity = new CharacterEntity();
                entity.setUserId(userId);
                entity.setNovelId(novelId);
                entity.setName(truncate(name, 64));
                entity.setAlias(truncate(text(node, "alias"), 64));
                entity.setGender(normalizeGender(text(node, "gender")));
                entity.setAgeDesc(truncate(text(node, "ageDesc"), 32));
                entity.setIdentity(truncate(text(node, "identity"), 255));
                entity.setRolePos(normalizeRole(text(node, "rolePos")));
                entity.setLifeStatus("ALIVE");
                Integer firstChapter = node.path("firstChapter").isInt() ? node.path("firstChapter").asInt() : null;
                entity.setFirstChapter(firstChapter == null || firstChapter < 1 ? 1 : firstChapter);
                String factionName = text(node, "faction");
                if (hasText(factionName) && factionIds.containsKey(factionName)) {
                    entity.setFactionId(factionIds.get(factionName));
                }
                entity.setProfileJson(writeProfile(node));
                entity.setSortNo(sort++);
                entity.setDeleted(0);
                entity.setVersion(0);
                characterMapper.insert(entity);
                characterIds.put(name, entity.getId());
            }
        }

        JsonNode relations = cast.path("relations");
        if (relations.isArray()) {
            int sort = 0;
            Set<String> seen = new LinkedHashSet<>();
            for (JsonNode node : relations) {
                Long from = characterIds.get(text(node, "from"));
                Long to = characterIds.get(text(node, "to"));
                if (from == null || to == null || from.equals(to)) {
                    continue;
                }
                String key = from + "-" + to + "-" + text(node, "type");
                if (!seen.add(key)) {
                    continue;
                }
                CharacterRelationEntity entity = new CharacterRelationEntity();
                entity.setUserId(userId);
                entity.setNovelId(novelId);
                entity.setFromCharacterId(from);
                entity.setToCharacterId(to);
                entity.setRelationType(normalizeRelationType(text(node, "type")));
                entity.setDescription(truncate(text(node, "description"), 500));
                entity.setSortNo(sort++);
                entity.setDeleted(0);
                entity.setVersion(0);
                characterRelationMapper.insert(entity);
            }
        }

        // 势力成员：人物档案里带了 faction 就补一条成员关系，工作台的势力卡片才有人
        for (JsonNode node : characters) {
            String name = text(node, "name");
            Long characterId = characterIds.get(name);
            String factionName = text(node, "faction");
            if (characterId == null || !hasText(factionName)) {
                continue;
            }
            Long factionId = factionIds.get(factionName);
            if (factionId == null) {
                continue;
            }
            FactionMemberEntity member = new FactionMemberEntity();
            member.setUserId(userId);
            member.setNovelId(novelId);
            member.setFactionId(factionId);
            member.setCharacterId(characterId);
            member.setPosition(truncate(text(node, "identity"), 64));
            member.setSortNo(0);
            member.setDeleted(0);
            member.setVersion(0);
            factionMemberMapper.insert(member);
        }
        return characterIds;
    }

    private int saveItems(Long novelId, Long userId, JsonNode items) {
        JsonNode array = items.path("items");
        if (!array.isArray()) {
            return 0;
        }
        int sort = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (JsonNode node : array) {
            String name = text(node, "name");
            if (!hasText(name) || !seen.add(name)) {
                continue;
            }
            ItemEntity entity = new ItemEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setName(truncate(name, 64));
            entity.setCategory(truncate(text(node, "category"), 32));
            entity.setOwner(truncate(text(node, "owner"), 64));
            entity.setOrigin(truncate(text(node, "origin"), 255));
            entity.setDescription(truncate(text(node, "description"), 2000));
            entity.setStatus(normalizeItemStatus(text(node, "status")));
            entity.setQuantity(1);
            entity.setSortNo(sort++);
            entity.setDeleted(0);
            entity.setVersion(0);
            itemMapper.insert(entity);
        }
        return sort;
    }

    private Long saveMasterOutline(Long novelId, Long userId, JsonNode master, int chapterCount) {
        String content = renderMaster(master);
        if (!hasText(content)) {
            return null;
        }
        MasterOutlineEntity entity = new MasterOutlineEntity();
        entity.setUserId(userId);
        entity.setNovelId(novelId);
        entity.setSortNo(1);
        entity.setTitle(truncate(text(master, "title"), 200));
        entity.setContent(content);
        entity.setStatus(MasterOutlineEntity.STATUS_RUNNING);
        entity.setPlannedOutlines(chapterCount);
        entity.setDeleted(0);
        entity.setVersion(0);
        masterOutlineMapper.insert(entity);
        // 已有章节挂到这条总纲上，续写时才知道「写到哪儿了」
        chapterMapper.update(null, Wrappers.<ChapterEntity>lambdaUpdate()
                .eq(ChapterEntity::getNovelId, novelId)
                .isNull(ChapterEntity::getMasterOutlineId)
                .set(ChapterEntity::getMasterOutlineId, entity.getId()));
        return entity.getId();
    }

    /** 逐章大纲：按批调用，把 summary 写回章节。返回成功回填的章节数。 */
    private int fillChapterOutlines(AiEndpoint base, Long novelId, List<ChapterRow> chapters,
                                    String masterText, Long masterId, Long taskId) {
        int outlined = 0;
        int total = chapters.size();
        for (int start = 0; start < total; start += OUTLINE_BATCH) {
            List<ChapterRow> batch = chapters.subList(start, Math.min(total, start + OUTLINE_BATCH));
            List<NovelImportPromptBuilder.ChapterBrief> briefs = new ArrayList<>(batch.size());
            for (ChapterRow row : batch) {
                briefs.add(new NovelImportPromptBuilder.ChapterBrief(
                        row.chapterNo(), row.title(), excerpt(row.content())));
            }
            int percent = 80 + (int) Math.round(16.0 * start / Math.max(1, total));
            JsonNode node = call(base, NovelImportPromptBuilder.SYSTEM,
                    NovelImportPromptBuilder.chapterOutlinePrompt(masterText, briefs),
                    3000, 0.3D, taskId, 7,
                    "正在回填章节大纲 " + Math.min(total, start + OUTLINE_BATCH) + "/" + total, percent);
            JsonNode array = node.path("chapters");
            if (!array.isArray()) {
                continue;
            }
            Map<Integer, String> summaries = new LinkedHashMap<>();
            for (JsonNode item : array) {
                int no = item.path("chapterNo").asInt(-1);
                String summary = text(item, "summary");
                if (no >= 0 && hasText(summary)) {
                    summaries.put(no, summary);
                }
            }
            for (ChapterRow row : batch) {
                String summary = summaries.get(row.chapterNo());
                if (summary == null) {
                    continue;
                }
                chapterMapper.update(null, Wrappers.<ChapterEntity>lambdaUpdate()
                        .eq(ChapterEntity::getId, row.id())
                        .set(ChapterEntity::getSummary, truncate(summary, 1000))
                        .set(ChapterEntity::getUpdatedAt, LocalDateTime.now()));
                outlined++;
            }
        }
        return outlined;
    }

    /* ------------------------------------------------------------------ */
    /* AI 调用                                                            */
    /* ------------------------------------------------------------------ */

    private JsonNode call(AiEndpoint base, String systemPrompt, String userPrompt, int maxTokens,
                          double temperature, Long id, int stepNo, String label, int percent) {
        progress(id, stepNo, label, percent, null);
        try {
            return parse(chat(base, systemPrompt, userPrompt, maxTokens, temperature).content(), label);
        } catch (BizException ex) {
            if (ex.getErrorCode() != ErrorCode.AI_PARSE_FAILED) {
                throw ex;
            }
            log.warn("[import] parse failed at {}, retrying taskId={}", label, id);
            progress(id, stepNo, label, Math.min(percent + 2, 99), "上一段返回格式不合法，正在重新生成");
            return parse(chat(base, systemPrompt + NovelImportPromptBuilder.RETRY_SUFFIX,
                    userPrompt, maxTokens, temperature).content(), label);
        }
    }

    private ChatResult chat(AiEndpoint base, String systemPrompt, String userPrompt,
                            int maxTokens, double temperature) {
        AiEndpoint endpoint = new AiEndpoint(
                base.baseUrl(),
                base.apiKey(),
                base.model(),
                Math.max(base.timeoutMs(), TIMEOUT_MS),
                base.extraHeaders(),
                temperature,
                maxTokens);
        try {
            return aiUsageService.chat(AiScene.IMPORT_ANALYZE, endpoint, List.of(
                    ChatMessage.system(systemPrompt),
                    ChatMessage.user(userPrompt)));
        } catch (BizException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw BizException.of(ErrorCode.AI_CALL_FAILED,
                    hasText(ex.getMessage()) ? ex.getMessage() : "AI 调用失败");
        }
    }

    private JsonNode parse(String content, String stage) {
        String candidate = LenientJson.extractObject(content);
        if (candidate.isEmpty()) {
            log.warn("[import] stage {} returned no JSON: {}", stage, abbreviate(content, 400));
            throw BizException.of(ErrorCode.AI_PARSE_FAILED, stage + "时模型没有返回结构化内容，请重试");
        }
        try {
            return objectMapper.readTree(candidate);
        } catch (Exception strictFailure) {
            try {
                return objectMapper.readTree(LenientJson.repair(candidate));
            } catch (Exception ex) {
                log.warn("[import] stage {} parse failed: {} | raw: {}",
                        stage, strictFailure.getMessage(), abbreviate(candidate, 400));
                throw BizException.of(ErrorCode.AI_PARSE_FAILED, stage + "时模型返回的内容无法解析，请重试");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 渲染与工具                                                         */
    /* ------------------------------------------------------------------ */

    private List<ChapterRow> loadChapters(Long novelId) {
        List<ChapterEntity> rows = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .orderByAsc(ChapterEntity::getSortNo));
        List<ChapterRow> result = new ArrayList<>(rows.size());
        for (ChapterEntity row : rows) {
            result.add(new ChapterRow(row.getId(),
                    row.getChapterNo() == null ? 0 : row.getChapterNo(),
                    row.getTitle() == null ? "" : row.getTitle(),
                    row.getContent() == null ? "" : row.getContent()));
        }
        return result;
    }

    private String rebuildText(List<ChapterRow> chapters) {
        StringBuilder builder = new StringBuilder(Math.min(64_000_000, chapters.size() * 4000));
        for (ChapterRow row : chapters) {
            builder.append(row.title()).append('\n').append(row.content()).append('\n');
        }
        return builder.toString();
    }

    /** 通读清单压缩成一段可读文本，作为后续阶段的输入。 */
    private String renderNotes(List<JsonNode> notes) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < notes.size(); i++) {
            JsonNode node = notes.get(i);
            builder.append("【第 ").append(i + 1).append(" 段】\n");
            JsonNode points = node.path("plotPoints");
            if (points.isArray()) {
                builder.append("事件：");
                for (JsonNode point : points) {
                    builder.append(point.asText("")).append("；");
                }
                builder.append('\n');
            }
            JsonNode characters = node.path("characters");
            if (characters.isArray()) {
                builder.append("人物：");
                for (JsonNode item : characters) {
                    builder.append(text(item, "name"));
                    String identity = text(item, "identity");
                    if (hasText(identity)) {
                        builder.append("（").append(identity).append("）");
                    }
                    String change = text(item, "change");
                    if (hasText(change)) {
                        builder.append("→").append(change);
                    }
                    builder.append("；");
                }
                builder.append('\n');
            }
            JsonNode items = node.path("items");
            if (items.isArray() && items.size() > 0) {
                builder.append("物品：");
                for (JsonNode item : items) {
                    builder.append(text(item, "name")).append("（").append(text(item, "desc")).append("）；");
                }
                builder.append('\n');
            }
            JsonNode factions = node.path("factions");
            if (factions.isArray() && factions.size() > 0) {
                builder.append("势力：");
                for (JsonNode item : factions) {
                    builder.append(text(item, "name")).append("（").append(text(item, "desc")).append("）；");
                }
                builder.append('\n');
            }
            JsonNode terms = node.path("terms");
            if (terms.isArray() && terms.size() > 0) {
                builder.append("专名：");
                for (JsonNode term : terms) {
                    builder.append(term.asText("")).append("、");
                }
                builder.append('\n');
            }
            String tone = text(node, "tone");
            if (hasText(tone)) {
                builder.append("语气：").append(tone).append('\n');
            }
        }
        return builder.toString();
    }

    private String renderSetting(JsonNode setting) {
        StringBuilder builder = new StringBuilder();
        builder.append("书名：").append(text(setting, "title")).append('\n');
        builder.append("题材：").append(text(setting, "genre")).append('\n');
        builder.append("简介：").append(text(setting, "synopsis")).append('\n');
        builder.append("世界观：").append(text(setting, "worldview")).append('\n');
        builder.append("背景：").append(text(setting, "background")).append('\n');
        builder.append("核心冲突：").append(text(setting, "coreConflict")).append('\n');
        builder.append("看点：").append(joinArray(setting.path("highlights"))).append('\n');
        builder.append("文风：").append(text(setting, "style")).append('\n');
        return builder.toString();
    }

    private String renderCast(JsonNode cast) {
        StringBuilder builder = new StringBuilder();
        JsonNode factions = cast.path("factions");
        if (factions.isArray()) {
            builder.append("势力：\n");
            for (JsonNode node : factions) {
                builder.append("- ").append(text(node, "name"))
                        .append("（").append(text(node, "category")).append("）：")
                        .append(text(node, "remark")).append('\n');
            }
        }
        JsonNode characters = cast.path("characters");
        if (characters.isArray()) {
            builder.append("人物：\n");
            for (JsonNode node : characters) {
                builder.append("- ").append(text(node, "name"))
                        .append("（").append(text(node, "rolePos")).append(" / ")
                        .append(text(node, "identity")).append("）：")
                        .append(text(node, "profile")).append('\n');
            }
        }
        return builder.toString();
    }

    /** 总纲渲染成 Markdown 文本，存进 t_master_outline.content。 */
    private String renderMaster(JsonNode master) {
        StringBuilder builder = new StringBuilder();
        String title = text(master, "title");
        if (hasText(title)) {
            builder.append("# ").append(title).append("\n\n");
        }
        String logline = text(master, "logline");
        if (hasText(logline)) {
            builder.append("**一句话主线**：").append(logline).append("\n\n");
        }
        String mainline = text(master, "mainline");
        if (hasText(mainline)) {
            builder.append("## 主线推进\n\n").append(mainline).append("\n\n");
        }
        JsonNode acts = master.path("acts");
        if (acts.isArray() && acts.size() > 0) {
            builder.append("## 分幕\n\n");
            for (JsonNode act : acts) {
                builder.append("### ").append(text(act, "name"));
                String range = text(act, "range");
                if (hasText(range)) {
                    builder.append("（").append(range).append("）");
                }
                builder.append("\n\n").append(text(act, "content")).append("\n\n");
            }
        }
        String ending = text(master, "ending");
        if (hasText(ending)) {
            builder.append("## 结局走向\n\n").append(ending).append("\n\n");
        }
        JsonNode threads = master.path("openThreads");
        if (threads.isArray() && threads.size() > 0) {
            builder.append("## 未收束的伏笔\n\n");
            for (JsonNode thread : threads) {
                builder.append("- ").append(thread.asText("")).append('\n');
            }
        }
        return builder.toString().trim();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private String joinArray(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                parts.add(value);
            }
        }
        return String.join("；", parts);
    }

    private String writeProfile(JsonNode node) {
        Map<String, String> profile = new LinkedHashMap<>();
        putIfText(profile, "personality", text(node, "personality"));
        putIfText(profile, "motivation", text(node, "motivation"));
        putIfText(profile, "arc", text(node, "arc"));
        putIfText(profile, "profile", text(node, "profile"));
        if (profile.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (Exception ex) {
            log.warn("[import] serialize profile failed: {}", ex.getMessage());
            return null;
        }
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private void applyNovelMeta(Long novelId, JsonNode setting) {
        String genre = truncate(text(setting, "genre"), 32);
        String synopsis = truncate(text(setting, "synopsis"), 2000);
        String tags = toTagsJson(setting.path("tags"));
        var update = Wrappers.<NovelEntity>lambdaUpdate()
                .eq(NovelEntity::getId, novelId)
                .set(NovelEntity::getUpdatedAt, LocalDateTime.now());
        if (hasText(genre)) {
            update.set(NovelEntity::getGenre, genre);
        }
        if (hasText(synopsis)) {
            update.set(NovelEntity::getIntro, synopsis);
        }
        if (tags != null) {
            update.set(NovelEntity::getTags, tags);
        }
        novelMapper.update(null, update);
    }

    private String toTagsJson(JsonNode array) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asText("").trim();
            if (!value.isEmpty() && tags.size() < 8) {
                tags.add(value);
            }
        }
        if (tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags);
        } catch (Exception ex) {
            return null;
        }
    }

    private String excerpt(String content) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String flat = content.replaceAll("\\s+", " ").trim();
        return flat.length() <= EXCERPT_CHARS ? flat : flat.substring(0, EXCERPT_CHARS) + "…";
    }

    private String guessTitle(String fileName, NovelChapterSplitter.Result split) {
        if (hasText(fileName)) {
            String name = fileName.replaceAll("\\.[A-Za-z0-9]{1,5}$", "");
            name = name.replaceAll("[（(]?\\d{2,4}年?\\d{0,2}月?\\d{0,2}日?[）)]?$", "").trim();
            if (name.length() >= 2 && name.length() <= 40
                    && !name.matches("^[0-9\\s_-]+$")
                    && !name.contains("全本") && !name.contains("完结")) {
                return name;
            }
        }
        if (!split.chapters().isEmpty()) {
            String first = split.chapters().get(0).title();
            if (hasText(first) && first.length() <= 20) {
                return first;
            }
        }
        return "导入的作品";
    }

    /** 建书时先不判题材：AI 回来会写回 t_novel.genre，这里留空比猜错更干净。 */
    private String guessGenre(NovelChapterSplitter.Result split) {
        return null;
    }

    private String normalizeGender(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.startsWith("M") && !value.startsWith("MIN")) {
            return "MALE";
        }
        if (value.startsWith("F") || value.contains("女")) {
            return "FEMALE";
        }
        return "UNKNOWN";
    }

    private String normalizeRole(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (value.contains("PROTAG") || value.contains("主角")) {
            return "PROTAGONIST";
        }
        if (value.contains("ANTAG") || value.contains("反派") || value.contains("对手")) {
            return "ANTAGONIST";
        }
        if (value.contains("MINOR") || value.contains("龙套")) {
            return "MINOR";
        }
        return "SUPPORTING";
    }

    /** 物品状态只能落在 t_item 的既有枚举里：ACTIVE / DESTROYED / ABANDONED / LOST / CONSUMED。 */
    private String normalizeItemStatus(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "LOST" -> "LOST";
            case "DESTROYED" -> "DESTROYED";
            case "CONSUMED" -> "CONSUMED";
            case "ABANDONED" -> "ABANDONED";
            default -> "ACTIVE";
        };
    }

    /**
     * 关系类型映射到 t_character_relation 的既有枚举（库里有 CHECK 约束，中文直接入库会失败）。
     */
    private String normalizeRelationType(String raw) {
        String value = raw == null ? "" : raw.trim();
        String upper = value.toUpperCase(Locale.ROOT);
        Set<String> allowed = Set.of("MASTER_APPRENTICE", "FAMILY", "SPOUSE", "LOVER",
                "FRIEND", "ALLY", "ENEMY", "RIVAL", "COLLEAGUE", "OTHER");
        if (allowed.contains(upper)) {
            return upper;
        }
        if (value.contains("师") || value.contains("徒")) {
            return "MASTER_APPRENTICE";
        }
        if (value.contains("父") || value.contains("母") || value.contains("兄") || value.contains("弟")
                || value.contains("姐") || value.contains("妹") || value.contains("亲") || value.contains("族")) {
            return "FAMILY";
        }
        if (value.contains("夫") || value.contains("妻") || value.contains("道侣")) {
            return "SPOUSE";
        }
        if (value.contains("恋") || value.contains("情")) {
            return "LOVER";
        }
        if (value.contains("友") || value.contains("知己")) {
            return "FRIEND";
        }
        if (value.contains("盟") || value.contains("合作")) {
            return "ALLY";
        }
        if (value.contains("仇") || value.contains("敌")) {
            return "ENEMY";
        }
        if (value.contains("对手") || value.contains("竞争")) {
            return "RIVAL";
        }
        if (value.contains("同门") || value.contains("同僚") || value.contains("上下")) {
            return "COLLEAGUE";
        }
        return "OTHER";
    }

    /* ------------------------------------------------------------------ */
    /* 状态维护                                                           */
    /* ------------------------------------------------------------------ */

    private void progress(Long id, int stepNo, String label, int percent, String message) {
        try {
            var update = Wrappers.<NovelImportEntity>lambdaUpdate()
                    .eq(NovelImportEntity::getId, id)
                    .set(NovelImportEntity::getStepNo, stepNo)
                    .set(NovelImportEntity::getStepLabel, truncate(label, 64))
                    .set(NovelImportEntity::getPercent, Math.min(percent, 99))
                    .set(NovelImportEntity::getUpdatedAt, LocalDateTime.now());
            if (message != null) {
                update.set(NovelImportEntity::getErrorMessage, truncate(message, 500));
            }
            importMapper.update(null, update);
        } catch (RuntimeException ex) {
            log.warn("[import] progress update failed id={}: {}", id, ex.getMessage());
        }
    }

    private void finish(Long id, ImportTaskVO.Summary summary) {
        String json;
        try {
            json = objectMapper.writeValueAsString(summary);
        } catch (Exception ex) {
            json = null;
        }
        importMapper.update(null, Wrappers.<NovelImportEntity>lambdaUpdate()
                .eq(NovelImportEntity::getId, id)
                .set(NovelImportEntity::getStatus, NovelImportEntity.STATUS_SUCCEEDED)
                .set(NovelImportEntity::getPercent, 100)
                .set(NovelImportEntity::getStepNo, STEP_TOTAL)
                .set(NovelImportEntity::getStepLabel, "导入完成")
                .set(NovelImportEntity::getResultJson, json)
                .set(NovelImportEntity::getErrorMessage, null)
                .set(NovelImportEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private void fail(Long id, String message) {
        importMapper.update(null, Wrappers.<NovelImportEntity>lambdaUpdate()
                .eq(NovelImportEntity::getId, id)
                .set(NovelImportEntity::getStatus, NovelImportEntity.STATUS_FAILED)
                .set(NovelImportEntity::getStepLabel, "导入失败")
                .set(NovelImportEntity::getErrorMessage, truncate(message, 500))
                .set(NovelImportEntity::getUpdatedAt, LocalDateTime.now()));
    }

    private NovelImportEntity require(String taskId) {
        Long id = parseId(taskId);
        NovelImportEntity entity = importMapper.selectOne(Wrappers.<NovelImportEntity>lambdaQuery()
                .eq(NovelImportEntity::getId, id));
        if (entity == null) {
            throw BizException.of(ErrorCode.IMPORT_NOT_FOUND);
        }
        Long userId = UserContextHolder.requireUserId();
        if (!userId.equals(entity.getUserId())) {
            throw BizException.of(ErrorCode.IMPORT_NOT_FOUND);
        }
        return entity;
    }

    private ImportTaskVO toVO(NovelImportEntity entity) {
        ImportTaskVO vo = new ImportTaskVO();
        vo.setTaskId(String.valueOf(entity.getId()));
        vo.setStatus(entity.getStatus());
        vo.setPercent(entity.getPercent());
        vo.setStepNo(entity.getStepNo());
        vo.setStepTotal(entity.getStepTotal());
        vo.setStepLabel(entity.getStepLabel());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setSourceName(entity.getSourceName());
        vo.setTitle(entity.getTitle());
        vo.setPenName(entity.getPenName());
        vo.setGenre(entity.getGenre());
        vo.setSourceChars(entity.getSourceChars());
        vo.setChapterCount(entity.getChapterCount());
        vo.setImportedChapters(entity.getImportedChapters());
        vo.setNovelId(entity.getNovelId());
        vo.setCreatedAt(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        if (hasText(entity.getResultJson())) {
            try {
                vo.setSummary(objectMapper.readValue(entity.getResultJson(), ImportTaskVO.Summary.class));
            } catch (Exception ex) {
                log.warn("[import] parse summary failed id={}: {}", entity.getId(), ex.getMessage());
            }
        }
        return vo;
    }

    private ImportSummaryVO toSummary(NovelImportEntity entity) {
        ImportSummaryVO vo = new ImportSummaryVO();
        vo.setTaskId(String.valueOf(entity.getId()));
        vo.setSourceName(entity.getSourceName());
        vo.setTitle(entity.getTitle());
        vo.setSourceChars(entity.getSourceChars());
        vo.setChapterCount(entity.getChapterCount());
        vo.setStatus(entity.getStatus());
        vo.setNovelId(entity.getNovelId());
        vo.setErrorMessage(entity.getErrorMessage());
        vo.setCreatedAt(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString());
        return vo;
    }

    /* ------------------------------------------------------------------ */
    /* 小工具                                                             */
    /* ------------------------------------------------------------------ */

    private static boolean isTextFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".text");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static String abbreviate(String value, int max) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("\\s+", " ");
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static Long parseId(String value) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException ex) {
            throw BizException.of(ErrorCode.IMPORT_NOT_FOUND);
        }
    }

    /**
     * 库里的一章（分析阶段只需这四个字段）。
     */
    private record ChapterRow(Long id, int chapterNo, String title, String content) {
    }
}
