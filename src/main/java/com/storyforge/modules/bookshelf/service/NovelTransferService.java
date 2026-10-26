package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.infra.storage.CoverStorage;
import com.storyforge.modules.bookshelf.dto.NovelCoverExport;
import com.storyforge.modules.bookshelf.dto.NovelExportPackage;
import com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity;
import com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity;
import com.storyforge.modules.bookshelf.entity.ChapterCharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity;
import com.storyforge.modules.bookshelf.entity.CharacterRelationEntity;
import com.storyforge.modules.bookshelf.entity.EventParticipantEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import com.storyforge.modules.bookshelf.mapper.ChangeHistoryMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterAnalysisMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterCharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterEventMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterAttributeMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterInventoryMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterRelationMapper;
import com.storyforge.modules.bookshelf.mapper.EventParticipantMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMemberMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import com.storyforge.modules.bookshelf.mapper.MasterOutlineMapper;
import com.storyforge.modules.bookshelf.mapper.SettingDocMapper;
import com.storyforge.modules.bookshelf.mapper.TimelineEventMapper;
import com.storyforge.modules.novel.agent.entity.NovelAgentMessageEntity;
import com.storyforge.modules.novel.agent.entity.NovelAgentSessionEntity;
import com.storyforge.modules.novel.agent.mapper.NovelAgentMessageMapper;
import com.storyforge.modules.novel.agent.mapper.NovelAgentSessionMapper;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.entity.NovelCoverEntity;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelCoverMapper;
import com.storyforge.modules.novel.mapper.NovelMapper;
import com.storyforge.modules.novel.service.NovelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 小说全量导出 / 导入。
 *
 * <p><b>导出</b>：把一部小说在当前站点下的全部业务数据打成一个版本化 JSON，封面图片以
 * Base64 内嵌，单文件即可搬走整本书。</p>
 *
 * <p><b>导入</b>：校验格式后在新站点<b>重建</b>整本书。旧雪花 id 全部丢弃，按
 * 「旧 id → 新 id」映射表重写所有外键（人物/势力/物品/事件/章节/总纲/分析/会话/封面），
 * 数据归属改为当前登录用户；书名冲突自动追加序号。</p>
 *
 * <p>只搬运「书的内容」，不搬运站点级数据：AI 用量、拆书记录、灵感会话、AI 接口配置均不导入。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelTransferService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> SOURCES = Set.of("MANUAL", "INSPIRATION", "DECONSTRUCT");
    private static final int MAX_COVERS = 5;
    private static final int MAX_TITLE_LENGTH = 200;

    private final NovelMapper novelMapper;
    private final NovelCoverMapper coverMapper;
    private final SettingDocMapper settingDocMapper;
    private final MasterOutlineMapper masterOutlineMapper;
    private final FactionMapper factionMapper;
    private final FactionMemberMapper factionMemberMapper;
    private final CharacterMapper characterMapper;
    private final CharacterRelationMapper characterRelationMapper;
    private final CharacterAttributeMapper characterAttributeMapper;
    private final CharacterInventoryMapper characterInventoryMapper;
    private final ItemMapper itemMapper;
    private final TimelineEventMapper timelineEventMapper;
    private final EventParticipantMapper eventParticipantMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterCharacterMapper chapterCharacterMapper;
    private final ChapterEventMapper chapterEventMapper;
    private final ChapterAnalysisMapper chapterAnalysisMapper;
    private final ChangeHistoryMapper changeHistoryMapper;
    private final NovelAgentSessionMapper agentSessionMapper;
    private final NovelAgentMessageMapper agentMessageMapper;
    private final CoverStorage coverStorage;
    private final NovelService novelService;

    /* ================================================================== */
    /*                              导出                                   */
    /* ================================================================== */

    /** 导出一部小说的全部内容。数据权限插件保证只能导出自己的书。 */
    public NovelExportPackage export(Long novelId) {
        NovelEntity novel = novelMapper.selectById(novelId);
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }

        NovelExportPackage pkg = new NovelExportPackage();
        pkg.setExportedAt(LocalDateTime.now().format(TS));
        pkg.setNovel(novel);

        pkg.setSettingDocs(settingDocMapper.selectList(Wrappers.<SettingDocEntity>lambdaQuery()
                .eq(SettingDocEntity::getNovelId, novelId)
                .orderByAsc(SettingDocEntity::getSort)
                .orderByAsc(SettingDocEntity::getId)));
        pkg.setMasterOutlines(masterOutlineMapper.selectList(Wrappers.<MasterOutlineEntity>lambdaQuery()
                .eq(MasterOutlineEntity::getNovelId, novelId)
                .orderByAsc(MasterOutlineEntity::getSortNo)
                .orderByAsc(MasterOutlineEntity::getId)));
        pkg.setFactions(factionMapper.selectList(Wrappers.<FactionEntity>lambdaQuery()
                .eq(FactionEntity::getNovelId, novelId)
                .orderByAsc(FactionEntity::getSortNo)
                .orderByAsc(FactionEntity::getId)));
        pkg.setFactionMembers(factionMemberMapper.selectList(Wrappers.<FactionMemberEntity>lambdaQuery()
                .eq(FactionMemberEntity::getNovelId, novelId)
                .orderByAsc(FactionMemberEntity::getSortNo)
                .orderByAsc(FactionMemberEntity::getId)));
        pkg.setCharacters(characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, novelId)
                .orderByAsc(CharacterEntity::getSortNo)
                .orderByAsc(CharacterEntity::getId)));
        pkg.setCharacterRelations(characterRelationMapper.selectList(Wrappers.<CharacterRelationEntity>lambdaQuery()
                .eq(CharacterRelationEntity::getNovelId, novelId)
                .orderByAsc(CharacterRelationEntity::getSortNo)
                .orderByAsc(CharacterRelationEntity::getId)));
        pkg.setCharacterAttributes(characterAttributeMapper.selectList(Wrappers.<CharacterAttributeEntity>lambdaQuery()
                .eq(CharacterAttributeEntity::getNovelId, novelId)
                .orderByAsc(CharacterAttributeEntity::getSortNo)
                .orderByAsc(CharacterAttributeEntity::getId)));
        pkg.setCharacterInventories(characterInventoryMapper.selectList(Wrappers.<CharacterInventoryEntity>lambdaQuery()
                .eq(CharacterInventoryEntity::getNovelId, novelId)
                .orderByAsc(CharacterInventoryEntity::getSortNo)
                .orderByAsc(CharacterInventoryEntity::getId)));
        pkg.setItems(itemMapper.selectList(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getNovelId, novelId)
                .orderByAsc(ItemEntity::getSortNo)
                .orderByAsc(ItemEntity::getId)));
        pkg.setTimelineEvents(timelineEventMapper.selectList(Wrappers.<TimelineEventEntity>lambdaQuery()
                .eq(TimelineEventEntity::getNovelId, novelId)
                .orderByAsc(TimelineEventEntity::getSortNo)
                .orderByAsc(TimelineEventEntity::getId)));
        pkg.setEventParticipants(eventParticipantMapper.selectList(Wrappers.<EventParticipantEntity>lambdaQuery()
                .eq(EventParticipantEntity::getNovelId, novelId)
                .orderByAsc(EventParticipantEntity::getSortNo)
                .orderByAsc(EventParticipantEntity::getId)));
        pkg.setChapters(chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .orderByAsc(ChapterEntity::getSortNo)
                .orderByAsc(ChapterEntity::getId)));
        pkg.setChapterCharacters(chapterCharacterMapper.selectList(Wrappers.<ChapterCharacterEntity>lambdaQuery()
                .eq(ChapterCharacterEntity::getNovelId, novelId)
                .orderByAsc(ChapterCharacterEntity::getSortNo)
                .orderByAsc(ChapterCharacterEntity::getId)));
        pkg.setChapterEvents(chapterEventMapper.selectList(Wrappers.<ChapterEventEntity>lambdaQuery()
                .eq(ChapterEventEntity::getNovelId, novelId)
                .orderByAsc(ChapterEventEntity::getSortNo)
                .orderByAsc(ChapterEventEntity::getId)));
        pkg.setChapterAnalyses(chapterAnalysisMapper.selectList(Wrappers.<ChapterAnalysisEntity>lambdaQuery()
                .eq(ChapterAnalysisEntity::getNovelId, novelId)
                .orderByAsc(ChapterAnalysisEntity::getChapterNo)
                .orderByAsc(ChapterAnalysisEntity::getId)));
        pkg.setChangeHistories(changeHistoryMapper.selectList(Wrappers.<ChangeHistoryEntity>lambdaQuery()
                .eq(ChangeHistoryEntity::getNovelId, novelId)
                .orderByAsc(ChangeHistoryEntity::getId)));
        pkg.setAgentSessions(agentSessionMapper.selectList(Wrappers.<NovelAgentSessionEntity>lambdaQuery()
                .eq(NovelAgentSessionEntity::getNovelId, novelId)
                .orderByAsc(NovelAgentSessionEntity::getId)));
        pkg.setAgentMessages(agentMessageMapper.selectList(Wrappers.<NovelAgentMessageEntity>lambdaQuery()
                .eq(NovelAgentMessageEntity::getNovelId, novelId)
                .orderByAsc(NovelAgentMessageEntity::getId)));

        pkg.setCovers(exportCovers(novelId));

        log.info("[transfer] exported novel id={} title={} chapters={} characters={} covers={}",
                novelId, novel.getTitle(),
                pkg.getChapters().size(), pkg.getCharacters().size(), pkg.getCovers().size());
        return pkg;
    }

    /** 封面：读文件转 Base64；文件已丢失时保留元数据、imageData 置空。 */
    private List<NovelCoverExport> exportCovers(Long novelId) {
        List<NovelCoverEntity> entities = coverMapper.selectList(Wrappers.<NovelCoverEntity>lambdaQuery()
                .eq(NovelCoverEntity::getNovelId, novelId)
                .orderByDesc(NovelCoverEntity::getIsPrimary)
                .orderByAsc(NovelCoverEntity::getSortNo));
        List<NovelCoverExport> result = new ArrayList<>(entities.size());
        for (NovelCoverEntity entity : entities) {
            NovelCoverExport dto = new NovelCoverExport();
            dto.setId(entity.getId());
            dto.setMimeType(entity.getMimeType());
            dto.setFileSize(entity.getFileSize());
            dto.setWidth(entity.getWidth());
            dto.setHeight(entity.getHeight());
            dto.setPrompt(entity.getPrompt());
            dto.setNegativePrompt(entity.getNegativePrompt());
            dto.setStyle(entity.getStyle());
            dto.setHasText(entity.getHasText());
            dto.setTitleText(entity.getTitleText());
            dto.setAuthorText(entity.getAuthorText());
            dto.setTextMode(entity.getTextMode());
            dto.setProvider(entity.getProvider());
            dto.setModel(entity.getModel());
            dto.setIsPrimary(entity.getIsPrimary());
            dto.setSortNo(entity.getSortNo());

            String path = entity.getFilePath();
            if (path != null && !path.isBlank()) {
                try {
                    Resource resource = coverStorage.load(path);
                    if (resource.exists()) {
                        byte[] bytes = resource.getInputStream().readAllBytes();
                        dto.setImageData(Base64.getEncoder().encodeToString(bytes));
                        if (dto.getFileSize() == null) {
                            dto.setFileSize(bytes.length);
                        }
                    } else {
                        log.warn("[transfer] cover file missing novelId={} path={}", novelId, path);
                    }
                } catch (Exception ex) {
                    log.warn("[transfer] cover read failed novelId={} path={} err={}",
                            novelId, path, ex.getMessage());
                }
            }
            result.add(dto);
        }
        return result;
    }

    /* ================================================================== */
    /*                              导入                                   */
    /* ================================================================== */

    /**
     * 把导出包重建为当前用户下的一本新书。
     *
     * <p>事务内完成全部落库；封面文件写在事务内、失败时手动清理（DB 回滚不会删文件）。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public NovelVO importPackage(NovelExportPackage pkg) {
        validate(pkg);
        Long userId = UserContextHolder.requireUserId();
        List<String> writtenFiles = new ArrayList<>();
        try {
            Long novelId = doImport(pkg, userId, writtenFiles);
            log.info("[transfer] imported novel id={} userId={} title={} chapters={} characters={}",
                    novelId, userId, pkg.getNovel().getTitle(),
                    sizeOf(pkg.getChapters()), sizeOf(pkg.getCharacters()));
            return novelService.detail(novelId);
        } catch (RuntimeException ex) {
            for (String path : writtenFiles) {
                try {
                    coverStorage.delete(path);
                } catch (Exception ignored) {
                    // 清理失败不影响主异常
                }
            }
            throw ex;
        }
    }

    private Long doImport(NovelExportPackage pkg, Long userId, List<String> writtenFiles) {
        /* ---------- 1) 小说主体 ---------- */
        NovelEntity novel = pkg.getNovel();
        novel.setId(null);
        novel.setUserId(userId);
        novel.setTitle(uniqueTitle(novel.getTitle()));
        novel.setStatus(normalizeStatus(novel.getStatus()));
        novel.setSource(SOURCES.contains(novel.getSource()) ? novel.getSource() : "MANUAL");
        novel.setInspirationSessionId(null);
        novel.setCoverPath(null);
        novel.setChapterCount(0);
        novel.setWordCount(0);
        novel.setDeleted(0);
        novel.setVersion(0);
        novel.setCreatedBy(userId);
        novel.setUpdatedBy(userId);
        novelMapper.insert(novel);
        Long novelId = novel.getId();

        /* ---------- 2) 设定文档 ---------- */
        for (SettingDocEntity e : safe(pkg.getSettingDocs())) {
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            settingDocMapper.insert(e);
        }

        /* ---------- 3) 总纲（章节要引用） ---------- */
        Map<Long, Long> outlineIds = new HashMap<>();
        for (MasterOutlineEntity e : safe(pkg.getMasterOutlines())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            masterOutlineMapper.insert(e);
            outlineIds.put(oldId, e.getId());
        }

        /* ---------- 4) 势力 ---------- */
        Map<Long, Long> factionIds = new HashMap<>();
        for (FactionEntity e : safe(pkg.getFactions())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            factionMapper.insert(e);
            factionIds.put(oldId, e.getId());
        }

        /* ---------- 5) 人物（引用势力） ---------- */
        Map<Long, Long> characterIds = new HashMap<>();
        for (CharacterEntity e : safe(pkg.getCharacters())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setFactionId(mapId(factionIds, e.getFactionId()));
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            characterMapper.insert(e);
            characterIds.put(oldId, e.getId());
        }

        /* ---------- 6) 势力成员 ---------- */
        for (FactionMemberEntity e : safe(pkg.getFactionMembers())) {
            Long newFaction = mapId(factionIds, e.getFactionId());
            Long newCharacter = mapId(characterIds, e.getCharacterId());
            if (newFaction == null || newCharacter == null) {
                continue;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setFactionId(newFaction);
            e.setCharacterId(newCharacter);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            factionMemberMapper.insert(e);
        }

        /* ---------- 7) 物品 ---------- */
        Map<Long, Long> itemIds = new HashMap<>();
        for (ItemEntity e : safe(pkg.getItems())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            itemMapper.insert(e);
            itemIds.put(oldId, e.getId());
        }

        /* ---------- 8) 人物属性 / 背包 ---------- */
        for (CharacterAttributeEntity e : safe(pkg.getCharacterAttributes())) {
            Long newCharacter = mapId(characterIds, e.getCharacterId());
            if (newCharacter == null) {
                continue;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setCharacterId(newCharacter);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            characterAttributeMapper.insert(e);
        }
        for (CharacterInventoryEntity e : safe(pkg.getCharacterInventories())) {
            Long newCharacter = mapId(characterIds, e.getCharacterId());
            if (newCharacter == null) {
                continue;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setCharacterId(newCharacter);
            e.setItemId(mapId(itemIds, e.getItemId()));
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            characterInventoryMapper.insert(e);
        }

        /* ---------- 9) 人物关系（from < to 不变式） ---------- */
        for (CharacterRelationEntity e : safe(pkg.getCharacterRelations())) {
            Long from = mapId(characterIds, e.getFromCharacterId());
            Long to = mapId(characterIds, e.getToCharacterId());
            if (from == null || to == null || from.equals(to)) {
                continue;
            }
            if (from > to) {
                Long tmp = from;
                from = to;
                to = tmp;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setFromCharacterId(from);
            e.setToCharacterId(to);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            characterRelationMapper.insert(e);
        }

        /* ---------- 10) 事件（时间线） ---------- */
        Map<Long, Long> eventIds = new HashMap<>();
        for (TimelineEventEntity e : safe(pkg.getTimelineEvents())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            timelineEventMapper.insert(e);
            eventIds.put(oldId, e.getId());
        }
        for (EventParticipantEntity e : safe(pkg.getEventParticipants())) {
            Long newEvent = mapId(eventIds, e.getEventId());
            Long newCharacter = mapId(characterIds, e.getCharacterId());
            if (newEvent == null || newCharacter == null) {
                continue;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setEventId(newEvent);
            e.setCharacterId(newCharacter);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            eventParticipantMapper.insert(e);
        }

        /* ---------- 11) 章节（引用总纲） ---------- */
        Map<Long, Long> chapterIds = new HashMap<>();
        long totalWords = 0L;
        for (ChapterEntity e : safe(pkg.getChapters())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setMasterOutlineId(mapId(outlineIds, e.getMasterOutlineId()));
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            chapterMapper.insert(e);
            chapterIds.put(oldId, e.getId());
            totalWords += e.getWordCount() == null ? 0L : e.getWordCount();
        }

        /* ---------- 12) 章节 ↔ 人物 / 事件 ---------- */
        for (ChapterCharacterEntity e : safe(pkg.getChapterCharacters())) {
            Long newChapter = mapId(chapterIds, e.getChapterId());
            Long newCharacter = mapId(characterIds, e.getCharacterId());
            if (newChapter == null || newCharacter == null) {
                continue;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setChapterId(newChapter);
            e.setCharacterId(newCharacter);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            chapterCharacterMapper.insert(e);
        }
        for (ChapterEventEntity e : safe(pkg.getChapterEvents())) {
            Long newChapter = mapId(chapterIds, e.getChapterId());
            Long newEvent = mapId(eventIds, e.getEventId());
            if (newChapter == null || newEvent == null) {
                continue;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setChapterId(newChapter);
            e.setEventId(newEvent);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            chapterEventMapper.insert(e);
        }

        /* ---------- 13) 章节分析 ---------- */
        Map<Long, Long> analysisIds = new HashMap<>();
        for (ChapterAnalysisEntity e : safe(pkg.getChapterAnalyses())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setChapterId(mapId(chapterIds, e.getChapterId()));
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            chapterAnalysisMapper.insert(e);
            analysisIds.put(oldId, e.getId());
        }

        /* ---------- 14) 修改履历（entityId 多态） ---------- */
        for (ChangeHistoryEntity e : safe(pkg.getChangeHistories())) {
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setChapterId(mapId(chapterIds, e.getChapterId()));
            e.setAnalysisId(mapId(analysisIds, e.getAnalysisId()));
            e.setEntityId(mapPolymorphicEntityId(e.getEntityType(), e.getEntityId(),
                    characterIds, itemIds, factionIds));
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            changeHistoryMapper.insert(e);
        }

        /* ---------- 15) 智能助手会话 ---------- */
        Map<Long, Long> sessionIds = new HashMap<>();
        for (NovelAgentSessionEntity e : safe(pkg.getAgentSessions())) {
            Long oldId = e.getId();
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            agentSessionMapper.insert(e);
            sessionIds.put(oldId, e.getId());
        }
        for (NovelAgentMessageEntity e : safe(pkg.getAgentMessages())) {
            Long newSession = mapId(sessionIds, e.getSessionId());
            Long oldSession = e.getSessionId();
            // 会话必须存在：缺失时归到本会话集合的首个，避免整段对话丢失
            if (newSession == null && !sessionIds.isEmpty()) {
                newSession = sessionIds.values().iterator().next();
            }
            if (newSession == null) {
                continue;
            }
            e.setId(null);
            e.setUserId(userId);
            e.setNovelId(novelId);
            e.setSessionId(newSession);
            e.setDeleted(0);
            e.setVersion(0);
            e.setCreatedBy(userId);
            e.setUpdatedBy(userId);
            agentMessageMapper.insert(e);
        }

        /* ---------- 16) 封面：先落文件再落记录 ---------- */
        String primaryPath = importCovers(pkg.getCovers(), userId, novelId, writtenFiles);

        /* ---------- 17) 回填统计与主封面 ---------- */
        var update = Wrappers.<NovelEntity>lambdaUpdate()
                .eq(NovelEntity::getId, novelId)
                .set(NovelEntity::getChapterCount, sizeOf(pkg.getChapters()))
                .set(NovelEntity::getWordCount, (int) Math.min(totalWords, Integer.MAX_VALUE));
        if (primaryPath != null) {
            update.set(NovelEntity::getCoverPath, primaryPath);
        }
        novelMapper.update(null, update);

        return novelId;
    }

    /**
     * 封面落盘 + 建记录，返回主封面相对路径。
     *
     * <p>新路径按 {@code 新用户/新小说/uuid.ext} 重建；最多保留 5 张，主封面唯一。</p>
     */
    private String importCovers(List<NovelCoverExport> covers, Long userId, Long novelId,
                                List<String> writtenFiles) {
        if (covers == null || covers.isEmpty()) {
            return null;
        }
        List<NovelCoverExport> sorted = new ArrayList<>(covers);
        sorted.sort((a, b) -> Integer.compare(
                a.getSortNo() == null ? Integer.MAX_VALUE : a.getSortNo(),
                b.getSortNo() == null ? Integer.MAX_VALUE : b.getSortNo()));

        String primaryPath = null;
        int index = 0;
        for (NovelCoverExport src : sorted) {
            if (index >= MAX_COVERS) {
                break;
            }
            if (src.getImageData() == null || src.getImageData().isBlank()) {
                continue;
            }
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(src.getImageData());
            } catch (IllegalArgumentException ex) {
                log.warn("[transfer] cover base64 invalid, skipped index={}", index);
                continue;
            }

            String relativePath = userId + "/" + novelId + "/" + UUID.randomUUID().toString().replace("-", "")
                    + extensionOf(src.getMimeType());
            coverStorage.save(relativePath, bytes);
            writtenFiles.add(relativePath);

            NovelCoverEntity entity = new NovelCoverEntity();
            entity.setUserId(userId);
            entity.setNovelId(novelId);
            entity.setFilePath(relativePath);
            entity.setMimeType(src.getMimeType() == null ? "image/jpeg" : src.getMimeType());
            entity.setFileSize(bytes.length);
            entity.setWidth(src.getWidth());
            entity.setHeight(src.getHeight());
            entity.setPrompt(src.getPrompt());
            entity.setNegativePrompt(src.getNegativePrompt());
            entity.setStyle(src.getStyle());
            entity.setHasText(src.getHasText());
            entity.setTitleText(src.getTitleText());
            entity.setAuthorText(src.getAuthorText());
            entity.setTextMode(src.getTextMode());
            entity.setProvider(src.getProvider());
            entity.setModel(src.getModel());
            entity.setSortNo(index);
            entity.setIsPrimary(primaryPath == null ? 1 : 0);
            entity.setDeleted(0);
            entity.setVersion(0);
            entity.setCreatedBy(userId);
            entity.setUpdatedBy(userId);
            coverMapper.insert(entity);

            if (primaryPath == null) {
                primaryPath = relativePath;
            }
            index++;
        }
        return primaryPath;
    }

    /* ================================================================== */
    /*                              工具                                   */
    /* ================================================================== */

    private void validate(NovelExportPackage pkg) {
        if (pkg == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "导入内容为空");
        }
        if (!NovelExportPackage.FORMAT.equals(pkg.getFormat())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "不是 StoryForge 的小说导出文件");
        }
        if (pkg.getFormatVersion() == null || pkg.getFormatVersion() > NovelExportPackage.FORMAT_VERSION) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "导出文件版本过高（当前支持 v" + NovelExportPackage.FORMAT_VERSION + "）");
        }
        if (pkg.getNovel() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "导出文件缺少小说主体数据");
        }
    }

    /** 书名同用户内唯一：重名自动追加序号。 */
    private String uniqueTitle(String raw) {
        String base = (raw == null || raw.isBlank()) ? "未命名小说" : raw.trim();
        if (base.length() > MAX_TITLE_LENGTH - 8) {
            base = base.substring(0, MAX_TITLE_LENGTH - 8);
        }
        String candidate = base;
        for (int i = 2; i <= 50; i++) {
            long exists = novelMapper.selectCount(Wrappers.<NovelEntity>lambdaQuery()
                    .eq(NovelEntity::getTitle, candidate));
            if (exists == 0L) {
                return candidate;
            }
            candidate = base + "（" + i + "）";
        }
        return base + "（" + System.currentTimeMillis() % 100000 + "）";
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            return "DRAFT";
        }
        String normalized = status.trim().toUpperCase();
        return Set.of("DRAFT", "WRITING", "FINISHED", "ARCHIVED").contains(normalized) ? normalized : "DRAFT";
    }

    private Long mapId(Map<Long, Long> mapping, Long oldId) {
        if (oldId == null) {
            return null;
        }
        return mapping.get(oldId);
    }

    /** 修改履历的 entity_id 是多态外键，按 entity_type 选对应的映射表。 */
    private Long mapPolymorphicEntityId(String entityType, Long oldId,
                                        Map<Long, Long> characterIds,
                                        Map<Long, Long> itemIds,
                                        Map<Long, Long> factionIds) {
        if (entityType == null || oldId == null) {
            return null;
        }
        return switch (entityType.trim().toUpperCase()) {
            case "CHARACTER" -> characterIds.get(oldId);
            case "ITEM" -> itemIds.get(oldId);
            case "FACTION" -> factionIds.get(oldId);
            default -> null;
        };
    }

    private String extensionOf(String mimeType) {
        if (mimeType == null) {
            return ".jpg";
        }
        return switch (mimeType.toLowerCase()) {
            case "image/png" -> ".png";
            case "image/webp" -> ".webp";
            default -> ".jpg";
        };
    }

    private <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private int sizeOf(List<?> list) {
        return list == null ? 0 : list.size();
    }
}
