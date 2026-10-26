package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WordCounts;
import com.storyforge.modules.bookshelf.dto.ChapterEventVO;
import com.storyforge.modules.bookshelf.dto.ChapterRequest;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.CharacterRelationVO;
import com.storyforge.modules.bookshelf.dto.CharacterSaveRequest;
import com.storyforge.modules.bookshelf.dto.CharacterTreeNode;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.EventParticipantVO;
import com.storyforge.modules.bookshelf.dto.ItemRequest;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import com.storyforge.modules.bookshelf.dto.RelationRequest;
import com.storyforge.modules.bookshelf.dto.TimelineRequest;
import com.storyforge.modules.bookshelf.dto.TimelineVO;
import com.storyforge.modules.bookshelf.entity.ChapterCharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.ChapterVersionEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterRelationEntity;
import com.storyforge.modules.bookshelf.entity.EventParticipantEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import com.storyforge.modules.bookshelf.entity.VolumeEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterAnalysisMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterCharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterEventMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterAttributeMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterInventoryMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterRelationMapper;
import com.storyforge.modules.bookshelf.mapper.ChangeHistoryMapper;
import com.storyforge.modules.bookshelf.mapper.EventParticipantMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMemberMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import com.storyforge.modules.bookshelf.mapper.MasterOutlineMapper;
import com.storyforge.modules.bookshelf.mapper.TimelineEventMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小说工作台要素服务实现。
 *
 * <p>章节使用 {@code sortNo = volumeNo * 1000 + chapterNo} 作为排序键，
 * 保证「按卷分组、卷内按章」的稳定顺序；每次章节增删改后重算小说的
 * {@code chapter_count} 与 {@code word_count}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelAssetServiceImpl implements NovelAssetService {

    private static final int CHAPTER_SORT_UNIT = 1000;

    /** 关系类型中文名，与前端下拉保持一致。 */
    private static final Map<String, String> RELATION_LABELS = createRelationLabels();

    /** 物品状态中文名。 */
    private static final Map<String, String> ITEM_STATUS_LABELS = Map.of(
            "ACTIVE", "活跃",
            "DESTROYED", "摧毁",
            "ABANDONED", "废弃",
            "LOST", "遗失",
            "CONSUMED", "已消耗");

    /** 事件状态中文名。 */
    private static final Map<String, String> EVENT_STATUS_LABELS = Map.of(
            "UNUSED", "未使用",
            "ONGOING", "进行中",
            "DONE", "已完成",
            "ABANDONED", "废弃");

    /** 章节「已完成」状态：正文定稿，不允许再改正文与标题 */
    private static final String CHAPTER_STATUS_DONE = "DONE";

    /** 自动保存写快照的最小间隔（分钟）；0 表示每次都写。见 shouldSnapshotAuto。 */
    @Value("${storyforge.version.auto-snapshot-minutes:30}")
    private int autoSnapshotMinutes;

    /** 树节点排序：主角优先，其次反派、配角、龙套。 */
    private static final Map<String, Integer> ROLE_PRIORITY = Map.of(
            "PROTAGONIST", 0,
            "ANTAGONIST", 1,
            "SUPPORTING", 2,
            "MINOR", 3);

    private final NovelMapper novelMapper;
    private final ChapterMapper chapterMapper;
    private final ChapterAnalysisMapper chapterAnalysisMapper;
    private final ChangeHistoryMapper changeHistoryMapper;
    private final ChapterCharacterMapper chapterCharacterMapper;
    private final ChapterEventMapper chapterEventMapper;
    private final TimelineEventMapper timelineEventMapper;
    private final ItemMapper itemMapper;
    private final CharacterMapper characterMapper;
    private final CharacterRelationMapper characterRelationMapper;
    private final EventParticipantMapper eventParticipantMapper;
    private final FactionMemberMapper factionMemberMapper;
    private final CharacterAttributeMapper characterAttributeMapper;
    private final CharacterInventoryMapper characterInventoryMapper;
    private final MasterOutlineMapper masterOutlineMapper;
    private final ChapterVersionService chapterVersionService;
    private final VolumeService volumeService;
    private final ObjectMapper objectMapper;

    /* ------------------------------------------------------------------ */
    /* 章节                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public List<ChapterVO> listChapters(Long novelId) {
        requireNovel(novelId);
        List<ChapterEntity> entities = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .orderByAsc(ChapterEntity::getSortNo)
                .orderByAsc(ChapterEntity::getChapterNo));
        Map<Long, CharacterEntity> characters = characterMap(novelId);
        Map<Long, List<EventParticipantVO>> participants = chapterParticipantMap(novelId, characters);
        Map<Long, List<ChapterEventVO>> events = chapterEventMap(novelId);
        return entities.stream()
                .map(entity -> toChapterVO(entity, false, participants, events))
                .toList();
    }

    @Override
    public ChapterVO getChapter(Long chapterId) {
        ChapterEntity entity = requireChapter(chapterId);
        Map<Long, CharacterEntity> characters = characterMap(entity.getNovelId());
        Map<Long, List<EventParticipantVO>> participants = chapterParticipantMap(entity.getNovelId(), characters);
        Map<Long, List<ChapterEventVO>> events = chapterEventMap(entity.getNovelId());
        return toChapterVO(entity, true, participants, events);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO createChapter(Long novelId, ChapterRequest request) {
        requireNovel(novelId);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        if (!hasText(request.getTitle())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请填写章节标题");
        }
        int volumeNo = request.getVolumeNo() == null ? 1 : request.getVolumeNo();
        int chapterNo = request.getChapterNo() == null ? nextChapterNo(novelId) : request.getChapterNo();
        // 卷是实体了：这一卷必须存在，否则卷列表查不到它，改卷名也没地方改
        VolumeEntity volume = volumeService.ensureVolume(novelId, volumeNo, request.getVolumeTitle());

        ChapterEntity entity = new ChapterEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        entity.setVolumeNo(volume.getVolumeNo());
        // 冗余卷名以卷实体为准：请求没带卷名时，这里要落卷表里已有的名字，
        // 否则建进一个已命名的卷却留着 null，列表与导出的卷名会缺失
        entity.setVolumeTitle(volume.getTitle());
        entity.setChapterNo(chapterNo);
        entity.setTitle(request.getTitle().trim());
        entity.setSummary(request.getSummary());
        entity.setContent(request.getContent());
        entity.setWordCount(countWords(request.getContent()));
        entity.setStatus(hasText(request.getStatus()) ? request.getStatus() : "DRAFT");
        entity.setMasterOutlineId(resolveMasterOutlineId(novelId, request.getMasterOutlineId()));
        entity.setSortNo(volumeNo * CHAPTER_SORT_UNIT + chapterNo);
        entity.setDeleted(0);
        entity.setVersion(0);
        chapterMapper.insert(entity);
        saveChapterLinks(novelId, entity.getId(), request.getParticipantIds(), request.getEventIds());
        recountNovel(novelId);
        return getChapter(entity.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO updateChapter(Long chapterId, ChapterRequest request) {
        return updateChapter(chapterId, request, ChapterVersionEntity.SOURCE_MANUAL, false, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO updateChapter(Long chapterId, ChapterRequest request, String source) {
        return updateChapter(chapterId, request, source, false, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO saveContent(Long chapterId, String content, Integer version) {
        ChapterEntity entity = requireChapter(chapterId);
        // 已定稿的章节正文锁定：自动保存不享有例外，前端本该不给编辑入口，这里再兜一次
        if (CHAPTER_STATUS_DONE.equals(entity.getStatus())) {
            throw BizException.of(ErrorCode.CHAPTER_LOCKED);
        }
        // 多端冲突：客户端带回来的版本号与库里不一致，说明别处已经改过
        if (version != null && !version.equals(entity.getVersion())) {
            throw BizException.of(ErrorCode.CHAPTER_CONFLICT);
        }
        String newContent = content == null ? "" : content.trim();
        String oldContent = nullToEmpty(entity.getContent());
        if (newContent.equals(oldContent)) {
            // 内容没变（例如停止输入后防抖又触发一次）——不写库、不存快照、不动字数
            return getChapter(chapterId);
        }
        Integer oldWordCount = entity.getWordCount();
        int newWordCount = countWords(newContent);
        // 只有正文在变，快照存「保存前」的旧稿；节流：距上一版 AUTO 不足设定分钟就跳过，
        // 否则一章编辑十分钟能刷出几十条版本
        if (shouldSnapshotAuto(chapterId)) {
            chapterVersionService.snapshot(
                    beforeSnapshot(entity, entity.getTitle(), oldContent, oldWordCount),
                    ChapterVersionEntity.SOURCE_AUTO, null);
        }
        entity.setContent(newContent);
        entity.setWordCount(newWordCount);
        int affected = chapterMapper.updateById(entity);
        // 乐观锁撞车（并发到同一行）：updateById 影响 0 行
        if (affected == 0) {
            throw BizException.of(ErrorCode.CHAPTER_CONFLICT);
        }
        // 增量修正全书字数：自动保存每次重算全书聚合太贵（500 章的书每秒都在算）
        int delta = newWordCount - (oldWordCount == null ? 0 : oldWordCount);
        if (delta != 0) {
            novelMapper.update(null, Wrappers.<NovelEntity>lambdaUpdate()
                    .eq(NovelEntity::getId, entity.getNovelId())
                    .setSql("word_count = word_count + (" + delta + ")"));
        }
        return getChapter(chapterId);
    }

    /**
     * 是否该为本次自动保存写快照。
     *
     * <p>节流窗口由 {@code storyforge.version.auto-snapshot-minutes} 控制（默认 30 分钟）：
     * 同一段连续编辑最多半小时留一版，配合「AUTO 每章最多 5 条 + 7 天」的清理策略，
     * 既能在崩溃后找回旧稿，又不会把版本列表刷爆。</p>
     */
    private boolean shouldSnapshotAuto(Long chapterId) {
        if (autoSnapshotMinutes <= 0) {
            return true;
        }
        LocalDateTime latest = chapterVersionService.latestAutoAt(chapterId);
        if (latest == null) {
            return true;
        }
        return Duration.between(latest, LocalDateTime.now()).toMinutes() >= autoSnapshotMinutes;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO restoreVersion(Long chapterId, Long versionId) {
        requireChapter(chapterId);
        ChapterVersionEntity version = chapterVersionService.require(chapterId, versionId);
        if (version.getContent() == null || version.getContent().isBlank()) {
            throw BizException.of(ErrorCode.CHAPTER_VERSION_NOT_FOUND, "该版本没有正文，无法回滚");
        }
        ChapterRequest request = new ChapterRequest();
        request.setContent(version.getContent());
        return updateChapter(chapterId, request, ChapterVersionEntity.SOURCE_RESTORE, true,
                "回滚到第 " + version.getVersionNo() + " 版前的稿子");
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChapterVO moveChapterToVolume(Long chapterId, Integer volumeNo) {
        ChapterEntity entity = requireChapter(chapterId);
        // 目标卷不存在就建一卷：允许「先挪过去、再取名」，不让作者卡在参数校验上
        VolumeEntity volume = volumeService.ensureVolume(entity.getNovelId(), volumeNo, null);
        ChapterRequest request = new ChapterRequest();
        request.setVolumeNo(volume.getVolumeNo());
        // 走章节更新的唯一收口：排序值在那里按卷号与章号重算
        return updateChapter(chapterId, request, ChapterVersionEntity.SOURCE_MANUAL);
    }

    /**
     * 更新章节的唯一实现。
     *
     * <p>{@code allowLocked} 只给回滚用：定稿章节不让从外部改正文，但作者自己要能撤销误操作，
     * 所以锁定判断在这里可以被内部绕过，且**不暴露成接口参数**。</p>
     */
    private ChapterVO updateChapter(Long chapterId, ChapterRequest request, String source,
                                    boolean allowLocked, String snapshotNote) {
        ChapterEntity entity = requireChapter(chapterId);
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        if (!allowLocked && isChapterContentLocked(entity, request)) {
            throw BizException.of(ErrorCode.CHAPTER_LOCKED);
        }
        // 快照存的是「保存前」的内容，所以在改 entity 之前把旧值留一份
        String oldContent = entity.getContent();
        String oldTitle = entity.getTitle();
        Integer oldWordCount = entity.getWordCount();
        boolean contentChanging = request.getContent() != null
                && !request.getContent().trim().equals(nullToEmpty(oldContent));
        if (request.getTitle() != null) {
            if (!hasText(request.getTitle())) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "章节标题不能为空");
            }
            entity.setTitle(request.getTitle().trim());
        }
        if (request.getVolumeNo() != null) {
            // 改归属卷：目标卷必须存在（不存在就建一卷），并把冗余卷名一并对齐
            VolumeEntity volume = volumeService.ensureVolume(entity.getNovelId(), request.getVolumeNo(),
                    request.getVolumeTitle());
            entity.setVolumeNo(request.getVolumeNo());
            entity.setVolumeTitle(volume.getTitle());
        } else if (request.getVolumeTitle() != null) {
            // 只改这一章的卷名：不通过卷表。这种情况是「同一卷里个别章节卷名写歪了」，
            // 让它改，但改名不会扩散到同卷其它章节——卷名的唯一写入方仍是 VolumeService。
            entity.setVolumeTitle(request.getVolumeTitle().trim());
        }
        if (request.getChapterNo() != null) {
            entity.setChapterNo(request.getChapterNo());
        }
        if (request.getSummary() != null) {
            // 空串而非 null：updateById 会跳过 null，导致「清空」失效
            entity.setSummary(request.getSummary().trim());
        }
        if (request.getContent() != null) {
            entity.setContent(request.getContent().trim());
            entity.setWordCount(countWords(request.getContent()));
        }
        if (hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        boolean clearMasterOutline = false;
        if (request.getMasterOutlineId() != null) {
            // 0 表示解除关联：updateById 会跳过 null，稍后单独 update 置空
            clearMasterOutline = request.getMasterOutlineId() <= 0;
            entity.setMasterOutlineId(clearMasterOutline
                    ? null : resolveMasterOutlineId(entity.getNovelId(), request.getMasterOutlineId()));
        }
        entity.setSortNo(entity.getVolumeNo() * CHAPTER_SORT_UNIT + entity.getChapterNo());
        if (contentChanging) {
            chapterVersionService.snapshot(beforeSnapshot(entity, oldTitle, oldContent, oldWordCount),
                    source, snapshotNote);
        }
        chapterMapper.updateById(entity);
        if (clearMasterOutline) {
            chapterMapper.update(null, Wrappers.<ChapterEntity>lambdaUpdate()
                    .set(ChapterEntity::getMasterOutlineId, null)
                    .eq(ChapterEntity::getId, chapterId));
        }
        saveChapterLinks(entity.getNovelId(), entity.getId(), request.getParticipantIds(), request.getEventIds());
        recountNovel(entity.getNovelId());
        return getChapter(entity.getId());
    }

    /**
     * 已完成章节的正文锁定判断。
     *
     * <p>只改状态（例如把「已完成」改回「草稿」/「写作中」）不算改内容，放行——这是解锁编辑的唯一入口；
     * 一旦请求里带了不同的正文或标题就直接拒绝，防止 AI 写正文、按分析修改等路径覆盖定稿。
     * 标题为空、正文为 null 时说明本次没提交该字段，不算修改。</p>
     */
    private boolean isChapterContentLocked(ChapterEntity entity, ChapterRequest request) {
        if (!CHAPTER_STATUS_DONE.equals(entity.getStatus())) {
            return false;
        }
        boolean titleChanged = hasText(request.getTitle())
                && !request.getTitle().trim().equals(nullToEmpty(entity.getTitle()));
        boolean contentChanged = request.getContent() != null
                && !request.getContent().trim().equals(nullToEmpty(entity.getContent()));
        return titleChanged || contentChanged;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /** 构造「保存前」的快照载体：只带快照需要的字段，避免把改过的值带进去。 */
    private ChapterEntity beforeSnapshot(ChapterEntity entity, String title, String content, Integer wordCount) {
        ChapterEntity before = new ChapterEntity();
        before.setId(entity.getId());
        before.setUserId(entity.getUserId());
        before.setNovelId(entity.getNovelId());
        before.setTitle(title);
        before.setContent(content);
        before.setWordCount(wordCount);
        return before;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteChapter(Long chapterId) {
        ChapterEntity entity = requireChapter(chapterId);
        deleteChapterLinks(chapterId);
        // 分析报告与变更履历都挂在章节上，章节删除后一并清理，避免出现指向空章的记录
        chapterAnalysisMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity::getChapterId, chapterId));
        changeHistoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getChapterId, chapterId));
        // 正文历史也挂在这一章上，章节没了历史就没有意义
        chapterVersionService.deleteByChapter(chapterId);
        chapterMapper.deleteById(chapterId);
        recountNovel(entity.getNovelId());
    }

    /* ------------------------------------------------------------------ */
    /* 时间线                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public List<TimelineVO> listTimeline(Long novelId) {
        requireNovel(novelId);
        Map<Long, CharacterEntity> characters = characterMap(novelId);
        Map<Long, List<EventParticipantVO>> participants = participantMap(novelId, characters);
        return timelineEventMapper.selectList(Wrappers.<TimelineEventEntity>lambdaQuery()
                        .eq(TimelineEventEntity::getNovelId, novelId)
                        .orderByAsc(TimelineEventEntity::getSortNo)
                        .orderByAsc(TimelineEventEntity::getId))
                .stream()
                .map(entity -> toTimelineVO(entity, participants.getOrDefault(entity.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TimelineVO createTimeline(Long novelId, TimelineRequest request) {
        requireNovel(novelId);
        TimelineEventEntity entity = new TimelineEventEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        entity.setEventTime(trimToNull(request.getEventTime()));
        entity.setTitle(request.getTitle().trim());
        entity.setDescription(trimToNull(request.getDescription()));
        entity.setStatus(hasText(request.getStatus()) ? request.getStatus() : "UNUSED");
        entity.setSortNo(request.getSortNo() == null ? nextSortNo(novelId, true) : request.getSortNo());
        entity.setDeleted(0);
        entity.setVersion(0);
        timelineEventMapper.insert(entity);
        saveParticipants(novelId, entity.getId(), request.getParticipantIds());
        return toTimelineVO(entity, participantsOf(novelId, entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TimelineVO updateTimeline(Long eventId, TimelineRequest request) {
        TimelineEventEntity entity = timelineEventMapper.selectById(eventId);
        if (entity == null) {
            throw BizException.of(ErrorCode.TIMELINE_NOT_FOUND);
        }
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            entity.setTitle(request.getTitle().trim());
        }
        if (request.getEventTime() != null) {
            entity.setEventTime(request.getEventTime().trim());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription().trim());
        }
        if (hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
        }
        timelineEventMapper.updateById(entity);
        if (request.getParticipantIds() != null) {
            saveParticipants(entity.getNovelId(), entity.getId(), request.getParticipantIds());
        }
        return toTimelineVO(entity, participantsOf(entity.getNovelId(), entity.getId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTimeline(Long eventId) {
        if (timelineEventMapper.selectById(eventId) == null) {
            throw BizException.of(ErrorCode.TIMELINE_NOT_FOUND);
        }
        eventParticipantMapper.delete(Wrappers.<EventParticipantEntity>lambdaQuery()
                .eq(EventParticipantEntity::getEventId, eventId));
        chapterEventMapper.delete(Wrappers.<ChapterEventEntity>lambdaQuery()
                .eq(ChapterEventEntity::getEventId, eventId));
        timelineEventMapper.deleteById(eventId);
    }

    /* ------------------------------------------------------------------ */
    /* 物品                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public List<ItemVO> listItems(Long novelId) {
        requireNovel(novelId);
        return itemMapper.selectList(Wrappers.<ItemEntity>lambdaQuery()
                        .eq(ItemEntity::getNovelId, novelId)
                        .orderByAsc(ItemEntity::getSortNo)
                        .orderByAsc(ItemEntity::getId))
                .stream()
                .map(this::toItemVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ItemVO createItem(Long novelId, ItemRequest request) {
        requireNovel(novelId);
        ItemEntity entity = new ItemEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        entity.setName(request.getName().trim());
        entity.setCategory(trimToNull(request.getCategory()));
        entity.setOwner(trimToNull(request.getOwner()));
        entity.setDescription(trimToNull(request.getDescription()));
        entity.setOrigin(trimToNull(request.getOrigin()));
        entity.setStatus(hasText(request.getStatus()) ? request.getStatus() : "ACTIVE");
        entity.setQuantity(request.getQuantity());
        entity.setSortNo(request.getSortNo() == null ? nextSortNo(novelId, false) : request.getSortNo());
        entity.setDeleted(0);
        entity.setVersion(0);
        itemMapper.insert(entity);
        return toItemVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ItemVO updateItem(Long itemId, ItemRequest request) {
        ItemEntity entity = itemMapper.selectById(itemId);
        if (entity == null) {
            throw BizException.of(ErrorCode.ITEM_NOT_FOUND);
        }
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            entity.setName(request.getName().trim());
        }
        if (request.getCategory() != null) {
            entity.setCategory(request.getCategory().trim());
        }
        if (request.getOwner() != null) {
            entity.setOwner(request.getOwner().trim());
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription().trim());
        }
        if (request.getOrigin() != null) {
            entity.setOrigin(request.getOrigin().trim());
        }
        if (hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        if (request.getQuantity() != null) {
            entity.setQuantity(request.getQuantity());
        }
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
        }
        itemMapper.updateById(entity);
        // updateById 会跳过 null，清空数量必须显式 set
        if (Boolean.TRUE.equals(request.getClearQuantity())) {
            itemMapper.update(null, Wrappers.<ItemEntity>lambdaUpdate()
                    .eq(ItemEntity::getId, itemId)
                    .set(ItemEntity::getQuantity, null));
            entity.setQuantity(null);
        }
        return toItemVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteItem(Long itemId) {
        if (itemMapper.selectById(itemId) == null) {
            throw BizException.of(ErrorCode.ITEM_NOT_FOUND);
        }
        changeHistoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getEntityType, "ITEM")
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getEntityId, itemId));
        // 物品档案删除后，背包里指向它的条目会变成悬空引用，一并清理
        characterInventoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getItemId, itemId));
        itemMapper.deleteById(itemId);
    }

    /* ------------------------------------------------------------------ */
    /* 角色                                                               */
    /* ------------------------------------------------------------------ */

    @Override
    public List<CharacterVO> listCharacters(Long novelId) {
        requireNovel(novelId);
        return characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                        .eq(CharacterEntity::getNovelId, novelId)
                        .orderByAsc(CharacterEntity::getSortNo)
                        .orderByAsc(CharacterEntity::getId))
                .stream()
                .map(this::toCharacterVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CharacterVO createCharacter(Long novelId, CharacterSaveRequest request) {
        requireNovel(novelId);
        CharacterEntity entity = new CharacterEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        applyCharacter(entity, request);
        int maxSort = characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                        .eq(CharacterEntity::getNovelId, novelId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0);
        entity.setSortNo("PROTAGONIST".equals(entity.getRolePos()) ? 0 : maxSort + 1);
        entity.setLifeStatus("ALIVE");
        entity.setDeleted(0);
        entity.setVersion(0);
        characterMapper.insert(entity);
        return toCharacterVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CharacterVO updateCharacter(Long characterId, CharacterSaveRequest request) {
        CharacterEntity entity = characterMapper.selectById(characterId);
        if (entity == null) {
            throw BizException.of(ErrorCode.CHARACTER_NOT_FOUND);
        }
        applyCharacter(entity, request);
        characterMapper.updateById(entity);
        return toCharacterVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteCharacter(Long characterId) {
        if (characterMapper.selectById(characterId) == null) {
            throw BizException.of(ErrorCode.CHARACTER_NOT_FOUND);
        }
        eventParticipantMapper.delete(Wrappers.<EventParticipantEntity>lambdaQuery()
                .eq(EventParticipantEntity::getCharacterId, characterId));
        chapterCharacterMapper.delete(Wrappers.<ChapterCharacterEntity>lambdaQuery()
                .eq(ChapterCharacterEntity::getCharacterId, characterId));
        factionMemberMapper.delete(Wrappers.<FactionMemberEntity>lambdaQuery()
                .eq(FactionMemberEntity::getCharacterId, characterId));
        // 关系随角色一起删除，避免关系视图里出现指向空节点的边
        characterRelationMapper.delete(Wrappers.<CharacterRelationEntity>lambdaQuery()
                .and(wrapper -> wrapper
                        .eq(CharacterRelationEntity::getFromCharacterId, characterId)
                        .or()
                        .eq(CharacterRelationEntity::getToCharacterId, characterId)));
        changeHistoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getEntityType, "CHARACTER")
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getEntityId, characterId));
        // 属性与背包属于角色私有数据，角色删除后没有归属，一并清理
        characterAttributeMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity::getCharacterId, characterId));
        characterInventoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getCharacterId, characterId));
        characterMapper.deleteById(characterId);
    }

    /* ------------------------------------------------------------------ */
    /* 角色关系                                                           */
    /* ------------------------------------------------------------------ */

    @Override
    public List<CharacterRelationVO> listRelations(Long novelId) {
        requireNovel(novelId);
        Map<Long, CharacterEntity> characters = characterMap(novelId);
        return characterRelationMapper.selectList(Wrappers.<CharacterRelationEntity>lambdaQuery()
                        .eq(CharacterRelationEntity::getNovelId, novelId)
                        .orderByAsc(CharacterRelationEntity::getSortNo)
                        .orderByAsc(CharacterRelationEntity::getId))
                .stream()
                .filter(item -> characters.containsKey(item.getFromCharacterId())
                        && characters.containsKey(item.getToCharacterId()))
                .map(item -> toRelationVO(item, characters))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CharacterRelationVO createRelation(Long novelId, RelationRequest request) {
        requireNovel(novelId);
        Long[] pair = normalizePair(novelId, request);
        ensureNoDuplicate(novelId, pair[0], pair[1], request.getRelationType(), null);

        CharacterRelationEntity entity = new CharacterRelationEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        applyRelation(entity, request, pair);
        entity.setSortNo(request.getSortNo() == null ? nextRelationSort(novelId) : request.getSortNo());
        entity.setDeleted(0);
        entity.setVersion(0);
        characterRelationMapper.insert(entity);
        return toRelationVO(entity, characterMap(novelId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CharacterRelationVO updateRelation(Long relationId, RelationRequest request) {
        CharacterRelationEntity entity = requireRelation(relationId);
        Long[] pair = normalizePair(entity.getNovelId(), request);
        ensureNoDuplicate(entity.getNovelId(), pair[0], pair[1], request.getRelationType(), relationId);
        applyRelation(entity, request, pair);
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
        }
        characterRelationMapper.updateById(entity);
        return toRelationVO(entity, characterMap(entity.getNovelId()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteRelation(Long relationId) {
        if (characterRelationMapper.selectById(relationId) == null) {
            throw BizException.of(ErrorCode.RELATION_NOT_FOUND);
        }
        characterRelationMapper.deleteById(relationId);
    }

    @Override
    public List<CharacterTreeNode> relationTree(Long novelId) {
        requireNovel(novelId);
        List<CharacterEntity> characters = characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                        .eq(CharacterEntity::getNovelId, novelId)
                        .orderByAsc(CharacterEntity::getSortNo)
                        .orderByAsc(CharacterEntity::getId));
        if (characters.isEmpty()) {
            return List.of();
        }
        Map<Long, CharacterEntity> byId = new LinkedHashMap<>();
        for (CharacterEntity item : characters) {
            byId.put(item.getId(), item);
        }

        List<CharacterRelationEntity> relations = characterRelationMapper.selectList(
                Wrappers.<CharacterRelationEntity>lambdaQuery()
                        .eq(CharacterRelationEntity::getNovelId, novelId)
                        .orderByAsc(CharacterRelationEntity::getSortNo)
                        .orderByAsc(CharacterRelationEntity::getId));

        Map<Long, List<Edge>> adjacency = new LinkedHashMap<>();
        for (CharacterRelationEntity relation : relations) {
            Long from = relation.getFromCharacterId();
            Long to = relation.getToCharacterId();
            if (!byId.containsKey(from) || !byId.containsKey(to)) {
                continue;
            }
            String label = relationLabel(relation.getRelationType());
            adjacency.computeIfAbsent(from, key -> new ArrayList<>()).add(new Edge(to, relation.getRelationType(), label));
            adjacency.computeIfAbsent(to, key -> new ArrayList<>()).add(new Edge(from, relation.getRelationType(), label));
        }

        // 主角优先作根，其余按 sortNo；孤立角色各自成一棵单节点树
        List<CharacterEntity> roots = new ArrayList<>(characters);
        roots.sort(Comparator
                .comparingInt((CharacterEntity item) -> ROLE_PRIORITY.getOrDefault(item.getRolePos(), 9))
                .thenComparingInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .thenComparing(CharacterEntity::getId));

        Set<Long> visited = new HashSet<>();
        List<CharacterTreeNode> forest = new ArrayList<>();
        for (CharacterEntity root : roots) {
            if (visited.contains(root.getId())) {
                continue;
            }
            forest.add(buildTreeNode(root, null, null, adjacency, byId, visited));
        }
        return forest;
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                           */
    /* ------------------------------------------------------------------ */

    private NovelEntity requireNovel(Long novelId) {
        NovelEntity novel = novelId == null ? null : novelMapper.selectById(novelId);
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        return novel;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity entity = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (entity == null) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND);
        }
        return entity;
    }

    private static Map<String, String> createRelationLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("MASTER_APPRENTICE", "师徒");
        labels.put("FAMILY", "亲属");
        labels.put("SPOUSE", "夫妻");
        labels.put("LOVER", "恋人");
        labels.put("FRIEND", "好友");
        labels.put("ALLY", "盟友");
        labels.put("ENEMY", "仇敌");
        labels.put("RIVAL", "对手");
        labels.put("COLLEAGUE", "同门");
        labels.put("OTHER", "其他");
        return Map.copyOf(labels);
    }

    private String relationLabel(String relationType) {
        return RELATION_LABELS.getOrDefault(relationType, "其他");
    }

    private Map<Long, CharacterEntity> characterMap(Long novelId) {
        Map<Long, CharacterEntity> map = new LinkedHashMap<>();
        for (CharacterEntity item : characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, novelId))) {
            map.put(item.getId(), item);
        }
        return map;
    }

    /** 全书事件 → 参与角色视图，调用方一次查完避免 N+1。 */
    private Map<Long, List<EventParticipantVO>> participantMap(Long novelId, Map<Long, CharacterEntity> characters) {
        Map<Long, List<EventParticipantVO>> map = new LinkedHashMap<>();
        for (EventParticipantEntity row : eventParticipantMapper.selectList(
                Wrappers.<EventParticipantEntity>lambdaQuery()
                        .eq(EventParticipantEntity::getNovelId, novelId)
                        .orderByAsc(EventParticipantEntity::getSortNo)
                        .orderByAsc(EventParticipantEntity::getId))) {
            CharacterEntity character = characters.get(row.getCharacterId());
            if (character == null) {
                continue;
            }
            map.computeIfAbsent(row.getEventId(), key -> new ArrayList<>())
                    .add(new EventParticipantVO(character.getId(), character.getName(),
                            roleLabel(character.getRolePos()), row.getRoleDesc()));
        }
        return map;
    }

    private List<EventParticipantVO> participantsOf(Long novelId, Long eventId) {
        return participantMap(novelId, characterMap(novelId)).getOrDefault(eventId, List.of());
    }

    /** 整体覆盖参与角色：先逻辑删除旧关联再插入，保证编辑后不残留。 */
    private void saveParticipants(Long novelId, Long eventId, List<Long> characterIds) {
        if (characterIds == null) {
            return;
        }
        eventParticipantMapper.delete(Wrappers.<EventParticipantEntity>lambdaQuery()
                .eq(EventParticipantEntity::getEventId, eventId));
        if (characterIds.isEmpty()) {
            return;
        }
        Map<Long, CharacterEntity> characters = characterMap(novelId);
        Set<Long> seen = new HashSet<>();
        int sort = 0;
        for (Long characterId : characterIds) {
            if (characterId == null || !seen.add(characterId) || !characters.containsKey(characterId)) {
                continue;
            }
            EventParticipantEntity row = new EventParticipantEntity();
            row.setUserId(UserContextHolder.requireUserId());
            row.setNovelId(novelId);
            row.setEventId(eventId);
            row.setCharacterId(characterId);
            row.setSortNo(sort++);
            row.setDeleted(0);
            row.setVersion(0);
            eventParticipantMapper.insert(row);
        }
    }

    /* ---------------- 章节关联：参与人物 / 事件 ---------------- */

    /** 一次查出全书章节人物关联，按 chapterId 分组，避免逐章查询。 */
    private Map<Long, List<EventParticipantVO>> chapterParticipantMap(Long novelId,
                                                                     Map<Long, CharacterEntity> characters) {
        Map<Long, List<EventParticipantVO>> map = new LinkedHashMap<>();
        for (ChapterCharacterEntity row : chapterCharacterMapper.selectList(
                Wrappers.<ChapterCharacterEntity>lambdaQuery()
                        .eq(ChapterCharacterEntity::getNovelId, novelId)
                        .orderByAsc(ChapterCharacterEntity::getSortNo)
                        .orderByAsc(ChapterCharacterEntity::getId))) {
            CharacterEntity character = characters.get(row.getCharacterId());
            if (character == null) {
                continue;
            }
            map.computeIfAbsent(row.getChapterId(), key -> new ArrayList<>())
                    .add(new EventParticipantVO(character.getId(), character.getName(),
                            roleLabel(character.getRolePos()), row.getRoleDesc()));
        }
        return map;
    }

    private Map<Long, List<ChapterEventVO>> chapterEventMap(Long novelId) {
        List<TimelineEventEntity> events = timelineEventMapper.selectList(
                Wrappers.<TimelineEventEntity>lambdaQuery().eq(TimelineEventEntity::getNovelId, novelId));
        Map<Long, TimelineEventEntity> byId = new LinkedHashMap<>();
        for (TimelineEventEntity event : events) {
            byId.put(event.getId(), event);
        }
        Map<Long, List<ChapterEventVO>> map = new LinkedHashMap<>();
        for (ChapterEventEntity row : chapterEventMapper.selectList(Wrappers.<ChapterEventEntity>lambdaQuery()
                .eq(ChapterEventEntity::getNovelId, novelId)
                .orderByAsc(ChapterEventEntity::getSortNo)
                .orderByAsc(ChapterEventEntity::getId))) {
            TimelineEventEntity event = byId.get(row.getEventId());
            if (event == null) {
                continue;
            }
            String status = hasText(event.getStatus()) ? event.getStatus() : "UNUSED";
            map.computeIfAbsent(row.getChapterId(), key -> new ArrayList<>())
                    .add(new ChapterEventVO(event.getId(), event.getTitle(), status,
                            EVENT_STATUS_LABELS.getOrDefault(status, status)));
        }
        return map;
    }

    /** 整体覆盖章节关联：传 null 表示不改，传空数组表示清空。 */
    private void saveChapterLinks(Long novelId, Long chapterId, List<Long> participantIds, List<Long> eventIds) {
        if (participantIds != null) {
            chapterCharacterMapper.delete(Wrappers.<ChapterCharacterEntity>lambdaQuery()
                    .eq(ChapterCharacterEntity::getChapterId, chapterId));
            Map<Long, CharacterEntity> characters = characterMap(novelId);
            Set<Long> seen = new HashSet<>();
            int sort = 0;
            for (Long characterId : participantIds) {
                if (characterId == null || !seen.add(characterId) || !characters.containsKey(characterId)) {
                    continue;
                }
                ChapterCharacterEntity row = new ChapterCharacterEntity();
                row.setUserId(UserContextHolder.requireUserId());
                row.setNovelId(novelId);
                row.setChapterId(chapterId);
                row.setCharacterId(characterId);
                row.setSortNo(sort++);
                row.setDeleted(0);
                row.setVersion(0);
                chapterCharacterMapper.insert(row);
            }
        }
        if (eventIds != null) {
            chapterEventMapper.delete(Wrappers.<ChapterEventEntity>lambdaQuery()
                    .eq(ChapterEventEntity::getChapterId, chapterId));
            Set<Long> validEvents = timelineEventMapper.selectList(Wrappers.<TimelineEventEntity>lambdaQuery()
                            .eq(TimelineEventEntity::getNovelId, novelId))
                    .stream()
                    .map(TimelineEventEntity::getId)
                    .collect(java.util.stream.Collectors.toSet());
            Set<Long> seen = new HashSet<>();
            int sort = 0;
            for (Long eventId : eventIds) {
                if (eventId == null || !seen.add(eventId) || !validEvents.contains(eventId)) {
                    continue;
                }
                ChapterEventEntity row = new ChapterEventEntity();
                row.setUserId(UserContextHolder.requireUserId());
                row.setNovelId(novelId);
                row.setChapterId(chapterId);
                row.setEventId(eventId);
                row.setSortNo(sort++);
                row.setDeleted(0);
                row.setVersion(0);
                chapterEventMapper.insert(row);
            }
        }
    }

    private void deleteChapterLinks(Long chapterId) {
        chapterCharacterMapper.delete(Wrappers.<ChapterCharacterEntity>lambdaQuery()
                .eq(ChapterCharacterEntity::getChapterId, chapterId));
        chapterEventMapper.delete(Wrappers.<ChapterEventEntity>lambdaQuery()
                .eq(ChapterEventEntity::getChapterId, chapterId));
    }

    private CharacterRelationEntity requireRelation(Long relationId) {
        CharacterRelationEntity entity = relationId == null ? null : characterRelationMapper.selectById(relationId);
        if (entity == null) {
            throw BizException.of(ErrorCode.RELATION_NOT_FOUND);
        }
        return entity;
    }

    /** 校验两端角色都属于该书且不相同，并按大小归一化，保证同一对角色不因录入顺序产生两行。 */
    private Long[] normalizePair(Long novelId, RelationRequest request) {
        if (request == null || request.getFromCharacterId() == null || request.getToCharacterId() == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请选择关系两端的角色");
        }
        if (request.getFromCharacterId().equals(request.getToCharacterId())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "不能给角色建立与自身的关系");
        }
        CharacterEntity first = characterMapper.selectById(request.getFromCharacterId());
        CharacterEntity second = characterMapper.selectById(request.getToCharacterId());
        if (first == null || second == null
                || !novelId.equals(first.getNovelId()) || !novelId.equals(second.getNovelId())) {
            throw BizException.of(ErrorCode.CHARACTER_NOT_FOUND, "关系两端的角色必须属于本书");
        }
        long low = Math.min(first.getId(), second.getId());
        long high = Math.max(first.getId(), second.getId());
        return new Long[]{low, high};
    }

    private void ensureNoDuplicate(Long novelId, Long fromId, Long toId, String relationType, Long excludeId) {
        Long exists = characterRelationMapper.selectCount(Wrappers.<CharacterRelationEntity>lambdaQuery()
                .eq(CharacterRelationEntity::getNovelId, novelId)
                .eq(CharacterRelationEntity::getFromCharacterId, fromId)
                .eq(CharacterRelationEntity::getToCharacterId, toId)
                .eq(CharacterRelationEntity::getRelationType, relationType)
                .ne(excludeId != null, CharacterRelationEntity::getId, excludeId));
        if (exists != null && exists > 0) {
            throw BizException.of(ErrorCode.RELATION_EXISTS);
        }
    }

    private void applyRelation(CharacterRelationEntity entity, RelationRequest request, Long[] pair) {
        entity.setFromCharacterId(pair[0]);
        entity.setToCharacterId(pair[1]);
        entity.setRelationType(request.getRelationType());
        entity.setDescription(request.getDescription() == null ? "" : request.getDescription().trim());
    }

    private int nextRelationSort(Long novelId) {
        return characterRelationMapper.selectList(Wrappers.<CharacterRelationEntity>lambdaQuery()
                        .eq(CharacterRelationEntity::getNovelId, novelId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    private CharacterRelationVO toRelationVO(CharacterRelationEntity entity, Map<Long, CharacterEntity> characters) {
        CharacterEntity from = characters.get(entity.getFromCharacterId());
        CharacterEntity to = characters.get(entity.getToCharacterId());
        return new CharacterRelationVO(
                entity.getId(),
                entity.getFromCharacterId(),
                from == null ? "未知角色" : from.getName(),
                entity.getToCharacterId(),
                to == null ? "未知角色" : to.getName(),
                entity.getRelationType(),
                relationLabel(entity.getRelationType()),
                entity.getDescription(),
                entity.getSortNo());
    }

    /** 深度优先构树；visited 保证环形关系不会无限递归，也保证每个角色只出现一次。 */
    private CharacterTreeNode buildTreeNode(CharacterEntity character,
                                            String relationType,
                                            String relationLabel,
                                            Map<Long, List<Edge>> adjacency,
                                            Map<Long, CharacterEntity> byId,
                                            Set<Long> visited) {
        visited.add(character.getId());
        List<Edge> edges = new ArrayList<>(adjacency.getOrDefault(character.getId(), List.of()));
        edges.sort(Comparator
                .comparingInt((Edge edge) -> ROLE_PRIORITY.getOrDefault(roleOf(byId, edge.characterId()), 9))
                .thenComparingInt(edge -> sortNoOf(byId, edge.characterId()))
                .thenComparing(Edge::characterId));
        List<CharacterTreeNode> children = new ArrayList<>();
        for (Edge edge : edges) {
            if (visited.contains(edge.characterId())) {
                continue;
            }
            CharacterEntity child = byId.get(edge.characterId());
            if (child == null) {
                continue;
            }
            children.add(buildTreeNode(child, edge.relationType(), edge.relationLabel(), adjacency, byId, visited));
        }
        return new CharacterTreeNode(
                character.getId(),
                character.getName(),
                character.getIdentity(),
                character.getRolePos(),
                roleLabel(character.getRolePos()),
                relationType,
                relationLabel,
                children);
    }

    private String roleOf(Map<Long, CharacterEntity> byId, Long characterId) {
        CharacterEntity entity = byId.get(characterId);
        return entity == null ? "" : entity.getRolePos();
    }

    private int sortNoOf(Map<Long, CharacterEntity> byId, Long characterId) {
        CharacterEntity entity = byId.get(characterId);
        return entity == null || entity.getSortNo() == null ? 0 : entity.getSortNo();
    }

    /** 关系图上的一条边（对面角色 + 关系类型）。 */
    private record Edge(Long characterId, String relationType, String relationLabel) {
    }

    private int nextChapterNo(Long novelId) {
        return chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                        .eq(ChapterEntity::getNovelId, novelId))
                .stream()
                .mapToInt(item -> item.getChapterNo() == null ? 0 : item.getChapterNo())
                .max()
                .orElse(0) + 1;
    }

    private int nextSortNo(Long novelId, boolean timeline) {
        if (timeline) {
            return timelineEventMapper.selectList(Wrappers.<TimelineEventEntity>lambdaQuery()
                            .eq(TimelineEventEntity::getNovelId, novelId))
                    .stream()
                    .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                    .max()
                    .orElse(0) + 1;
        }
        return itemMapper.selectList(Wrappers.<ItemEntity>lambdaQuery()
                        .eq(ItemEntity::getNovelId, novelId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    /** 重算章数与总字数，保证书架卡片上的统计与章节表一致。 */
    private void recountNovel(Long novelId) {
        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId));
        int count = chapters.size();
        int words = chapters.stream().mapToInt(item -> item.getWordCount() == null ? 0 : item.getWordCount()).sum();
        novelMapper.update(null, Wrappers.<NovelEntity>lambdaUpdate()
                .eq(NovelEntity::getId, novelId)
                .set(NovelEntity::getChapterCount, count)
                .set(NovelEntity::getWordCount, words));
    }

    private void applyCharacter(CharacterEntity entity, CharacterSaveRequest request) {
        if (request == null || !hasText(request.getName())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请填写角色姓名");
        }
        entity.setName(request.getName().trim());
        entity.setRolePos(normalizeRole(request.getRolePos()));
        entity.setIdentity(truncate(request.getIdentity(), 255));
        entity.setGender(truncate(request.getGender(), 16));
        entity.setAgeDesc(truncate(request.getAgeDesc(), 32));

        Map<String, String> profile = new LinkedHashMap<>();
        putIfText(profile, "appearance", request.getAppearance());
        putIfText(profile, "personality", request.getPersonality());
        putIfText(profile, "motivation", request.getMotivation());
        putIfText(profile, "goldenFinger", request.getGoldenFinger());
        putIfText(profile, "arc", request.getArc());
        entity.setProfileJson(profile.isEmpty() ? "" : writeProfile(profile));
    }

    private String writeProfile(Map<String, String> profile) {
        try {
            return objectMapper.writeValueAsString(profile);
        } catch (Exception ex) {
            log.warn("[asset] serialize profile failed: {}", ex.getMessage());
            return null;
        }
    }

    private Map<String, String> readProfile(String raw) {
        if (!hasText(raw)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<LinkedHashMap<String, String>>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (hasText(value)) {
            target.put(key, value.trim());
        }
    }

    private String normalizeRole(String raw) {
        String value = raw == null ? "" : raw.trim().toUpperCase();
        return switch (value) {
            case "PROTAGONIST", "SUPPORTING", "ANTAGONIST", "MINOR" -> value;
            default -> {
                if (value.contains("主角") || value.contains("PROTAG")) {
                    yield "PROTAGONIST";
                }
                if (value.contains("反派") || value.contains("对手") || value.contains("ANTAG")) {
                    yield "ANTAGONIST";
                }
                if (value.contains("龙套") || value.contains("路人")) {
                    yield "MINOR";
                }
                yield "SUPPORTING";
            }
        };
    }

    private String roleLabel(String rolePos) {
        if (rolePos == null) {
            return "角色";
        }
        return switch (rolePos) {
            case "PROTAGONIST" -> "主角";
            case "ANTAGONIST" -> "反派";
            case "MINOR" -> "龙套";
            default -> "配角";
        };
    }

    /** 校验总纲归属：null / 0 表示不关联，其余必须是本书的总纲。 */
    private Long resolveMasterOutlineId(Long novelId, Long masterOutlineId) {
        if (masterOutlineId == null || masterOutlineId <= 0) {
            return null;
        }
        MasterOutlineEntity outline = masterOutlineMapper.selectById(masterOutlineId);
        if (outline == null || !novelId.equals(outline.getNovelId())) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "总纲不存在或不属于这本书");
        }
        return masterOutlineId;
    }

    private ChapterVO toChapterVO(ChapterEntity entity,
                                  boolean withContent,
                                  Map<Long, List<EventParticipantVO>> participants,
                                  Map<Long, List<ChapterEventVO>> events) {
        return new ChapterVO(
                entity.getId(),
                entity.getVolumeNo(),
                entity.getVolumeTitle(),
                entity.getChapterNo(),
                entity.getTitle(),
                entity.getSummary(),
                withContent ? entity.getContent() : null,
                entity.getWordCount(),
                entity.getStatus(),
                entity.getVersion(),
                entity.getUpdatedAt(),
                entity.getNovelId(),
                participants.getOrDefault(entity.getId(), List.of()),
                events.getOrDefault(entity.getId(), List.of()),
                entity.getMasterOutlineId());
    }

    private TimelineVO toTimelineVO(TimelineEventEntity entity, List<EventParticipantVO> participants) {
        String status = hasText(entity.getStatus()) ? entity.getStatus() : "UNUSED";
        return new TimelineVO(entity.getId(), entity.getEventTime(), entity.getTitle(),
                entity.getDescription(), status,
                EVENT_STATUS_LABELS.getOrDefault(status, status),
                participants == null ? List.of() : participants,
                entity.getSortNo(), entity.getUpdatedAt());
    }

    private ItemVO toItemVO(ItemEntity entity) {
        String status = hasText(entity.getStatus()) ? entity.getStatus() : "ACTIVE";
        return new ItemVO(entity.getId(), entity.getName(), entity.getCategory(),
                entity.getOwner(), entity.getDescription(), entity.getOrigin(), status,
                ITEM_STATUS_LABELS.getOrDefault(status, status),
                entity.getQuantity(), entity.getSortNo(), entity.getUpdatedAt());
    }

    private CharacterVO toCharacterVO(CharacterEntity entity) {
        CharacterVO vo = new CharacterVO();
        vo.setId(entity.getId());
        vo.setName(entity.getName());
        vo.setRolePos(entity.getRolePos());
        vo.setRoleLabel(roleLabel(entity.getRolePos()));
        vo.setGender(entity.getGender());
        vo.setAgeDesc(entity.getAgeDesc());
        vo.setIdentity(entity.getIdentity());
        Map<String, String> profile = readProfile(entity.getProfileJson());
        vo.setAppearance(profile.get("appearance"));
        vo.setPersonality(profile.get("personality"));
        vo.setMotivation(profile.get("motivation"));
        vo.setGoldenFinger(profile.get("goldenFinger"));
        vo.setArc(profile.get("arc"));
        return vo;
    }

    /** 字数口径：只统计汉字，标点、数字与字母不计。 */
    private int countWords(String content) {
        return WordCounts.countChinese(content);
    }

    private String truncate(String value, int max) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
