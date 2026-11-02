package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.bookshelf.dto.ChapterFactsVO;
import com.storyforge.modules.bookshelf.dto.ForeshadowRequest;
import com.storyforge.modules.bookshelf.dto.ForeshadowVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterStateEntity;
import com.storyforge.modules.bookshelf.entity.ForeshadowEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterStateMapper;
import com.storyforge.modules.bookshelf.mapper.ForeshadowMapper;
import com.storyforge.modules.novel.mapper.NovelMapper;
import com.storyforge.modules.autowrite.support.AutoWriteGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事实层：章节事实摘要、角色状态快照、伏笔台账。
 *
 * <p>三样东西都不是给「档案」用的，是给「下一章」用的：
 * 正文提示词靠它们知道前面真正发生了什么、人物现在在哪、哪些线还欠着。
 * 所以这里的写入口都要过 {@link AutoWriteGuard}——自动写书正在跑的时候手工改事实层，
 * 会让流水线前后读到两套互相矛盾的前情。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterFactService {

    /** 计划未定回收章时的默认周期：埋设章之后这么多章还没收就算超期（与规则层 R9 保持一致） */
    public static final int DEFAULT_PAYOFF_CHAPTERS = 30;

    private final ChapterMapper chapterMapper;
    private final NovelMapper novelMapper;
    private final CharacterMapper characterMapper;
    private final CharacterStateMapper characterStateMapper;
    private final ForeshadowMapper foreshadowMapper;
    private final AutoWriteGuard autoWriteGuard;

    /* ------------------------------------------------------------------ */
    /* 章节事实                                                           */
    /* ------------------------------------------------------------------ */

    /** 本章事实层：事实摘要 + 本章埋的 / 收的伏笔 + 本章有状态变化的角色。 */
    public ChapterFactsVO detail(Long chapterId) {
        ChapterEntity chapter = requireChapter(chapterId);
        List<ForeshadowEntity> all = foreshadowMapper.selectList(
                Wrappers.<ForeshadowEntity>lambdaQuery()
                        .eq(ForeshadowEntity::getNovelId, chapter.getNovelId()));

        List<ForeshadowVO> planted = new ArrayList<>();
        List<ForeshadowVO> paid = new ArrayList<>();
        int latest = latestChapterNo(chapter.getNovelId());
        for (ForeshadowEntity item : all) {
            if (chapter.getChapterNo() != null && chapter.getChapterNo().equals(item.getPlantedChapterNo())) {
                planted.add(toVO(item, latest));
            }
            if (chapter.getChapterNo() != null && chapter.getChapterNo().equals(item.getPayoffChapterNo())) {
                paid.add(toVO(item, latest));
            }
        }

        List<ChapterFactsVO.CharacterStateVO> states = new ArrayList<>();
        List<CharacterStateEntity> rows = characterStateMapper.selectList(
                Wrappers.<CharacterStateEntity>lambdaQuery()
                        .eq(CharacterStateEntity::getNovelId, chapter.getNovelId())
                        .eq(CharacterStateEntity::getChapterNo, chapter.getChapterNo()));
        if (!rows.isEmpty()) {
            Map<Long, String> names = new HashMap<>();
            for (CharacterEntity character : characterMapper.selectList(
                    Wrappers.<CharacterEntity>lambdaQuery()
                            .eq(CharacterEntity::getNovelId, chapter.getNovelId()))) {
                names.put(character.getId(), character.getName());
            }
            for (CharacterStateEntity row : rows) {
                states.add(new ChapterFactsVO.CharacterStateVO(
                        row.getCharacterId(),
                        names.getOrDefault(row.getCharacterId(), "未知角色"),
                        row.getChapterNo(),
                        row.getLocation(),
                        row.getConditionText(),
                        row.getMood(),
                        splitLines(row.getKnowsAdded()),
                        splitLines(row.getKnowsHidden())));
            }
        }
        return new ChapterFactsVO(chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                chapter.getFactSummary(), chapter.getFactSource(), planted, paid, states);
    }

    /** 手工改事实摘要：来源置 MANUAL，之后重新分析不覆盖。 */
    @Transactional(rollbackFor = Exception.class)
    public ChapterFactsVO updateFactSummary(Long chapterId, String factSummary) {
        ChapterEntity chapter = requireChapter(chapterId);
        autoWriteGuard.assertEditable(chapter.getNovelId());
        String value = factSummary == null ? null : factSummary.trim();
        chapterMapper.update(null, Wrappers.<ChapterEntity>lambdaUpdate()
                .eq(ChapterEntity::getId, chapterId)
                .set(ChapterEntity::getFactSummary, value == null || value.isEmpty() ? null : value)
                .set(ChapterEntity::getFactSource,
                        value == null || value.isEmpty() ? null : ChapterEntity.FACT_SOURCE_MANUAL)
                .set(ChapterEntity::getUpdatedAt, java.time.LocalDateTime.now()));
        return detail(chapterId);
    }

    /* ------------------------------------------------------------------ */
    /* 伏笔台账                                                           */
    /* ------------------------------------------------------------------ */

    public List<ForeshadowVO> list(Long novelId, String status) {
        requireNovel(novelId);
        List<ForeshadowEntity> rows = foreshadowMapper.selectList(
                Wrappers.<ForeshadowEntity>lambdaQuery()
                        .eq(ForeshadowEntity::getNovelId, novelId)
                        .eq(status != null && !status.isBlank(), ForeshadowEntity::getStatus,
                                status == null ? null : status.trim().toUpperCase())
                        .orderByAsc(ForeshadowEntity::getPlantedChapterNo)
                        .orderByAsc(ForeshadowEntity::getId));
        int latest = latestChapterNo(novelId);
        return rows.stream().map(row -> toVO(row, latest)).toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ForeshadowVO create(Long novelId, ForeshadowRequest request) {
        requireNovel(novelId);
        autoWriteGuard.assertEditable(novelId);
        if (request == null || request.getTitle() == null || request.getTitle().isBlank()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "伏笔标题不能为空");
        }
        ForeshadowEntity entity = new ForeshadowEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        entity.setTitle(request.getTitle().trim());
        entity.setContent(trimToNull(request.getContent()));
        Integer planted = request.getPlantedChapterNo();
        entity.setPlantedChapterNo(planted != null && planted > 0 ? planted : latestChapterNo(novelId));
        entity.setPlannedPayoffChapter(normalizePayoff(request.getPlannedPayoffChapter()));
        entity.setStatus(normalizeStatus(request.getStatus(), ForeshadowEntity.STATUS_OPEN));
        entity.setNote(trimToNull(request.getNote()));
        entity.setDeleted(0);
        entity.setVersion(0);
        foreshadowMapper.insert(entity);
        return toVO(entity, latestChapterNo(novelId));
    }

    @Transactional(rollbackFor = Exception.class)
    public ForeshadowVO update(Long id, ForeshadowRequest request) {
        ForeshadowEntity entity = requireForeshadow(id);
        autoWriteGuard.assertEditable(entity.getNovelId());
        if (request == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请求内容不能为空");
        }
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            entity.setTitle(request.getTitle().trim());
        }
        if (request.getContent() != null) {
            entity.setContent(trimToNull(request.getContent()));
        }
        if (request.getPlantedChapterNo() != null && request.getPlantedChapterNo() > 0) {
            entity.setPlantedChapterNo(request.getPlantedChapterNo());
        }
        if (request.getPlannedPayoffChapter() != null) {
            entity.setPlannedPayoffChapter(normalizePayoff(request.getPlannedPayoffChapter()));
        }
        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            String status = normalizeStatus(request.getStatus(), entity.getStatus());
            if (ForeshadowEntity.STATUS_PAID.equals(status)) {
                if (ForeshadowEntity.STATUS_PAID.equals(entity.getStatus())) {
                    throw BizException.of(ErrorCode.PARAM_INVALID, "这条伏笔已经标记过回收了");
                }
                // 手工标记回收：回收章取当前最新章
                entity.setPayoffChapterNo(latestChapterNo(entity.getNovelId()));
            }
            if (ForeshadowEntity.STATUS_OPEN.equals(status)) {
                entity.setPayoffChapterNo(null);
                entity.setPayoffChapterId(null);
            }
            entity.setStatus(status);
        }
        if (request.getNote() != null) {
            entity.setNote(trimToNull(request.getNote()));
        }
        foreshadowMapper.updateById(entity);
        return toVO(entity, latestChapterNo(entity.getNovelId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        ForeshadowEntity entity = requireForeshadow(id);
        autoWriteGuard.assertEditable(entity.getNovelId());
        foreshadowMapper.deleteById(id);
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                               */
    /* ------------------------------------------------------------------ */

    public ForeshadowVO toVO(ForeshadowEntity entity, int latestChapterNo) {
        boolean open = ForeshadowEntity.STATUS_OPEN.equals(entity.getStatus());
        int dueAt = 0;
        int overdueChapters = 0;
        boolean overdue = false;
        if (open && entity.getPlantedChapterNo() != null) {
            Integer planned = entity.getPlannedPayoffChapter();
            dueAt = planned != null && planned > 0 ? planned : entity.getPlantedChapterNo() + DEFAULT_PAYOFF_CHAPTERS;
            if (latestChapterNo > dueAt) {
                overdue = true;
                overdueChapters = latestChapterNo - dueAt;
            }
        }
        return new ForeshadowVO(entity.getId(), entity.getTitle(), entity.getContent(),
                entity.getPlantedChapterNo(), entity.getPlannedPayoffChapter(),
                entity.getStatus(), statusLabel(entity.getStatus()),
                entity.getPayoffChapterNo(), overdue, overdueChapters, entity.getNote());
    }

    private String statusLabel(String status) {
        if (ForeshadowEntity.STATUS_PAID.equals(status)) {
            return "已回收";
        }
        if (ForeshadowEntity.STATUS_DROPPED.equals(status)) {
            return "已放弃";
        }
        return "未回收";
    }

    private String normalizeStatus(String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String upper = raw.trim().toUpperCase();
        if (!Arrays.asList(ForeshadowEntity.STATUS_OPEN, ForeshadowEntity.STATUS_PAID,
                ForeshadowEntity.STATUS_DROPPED).contains(upper)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "伏笔状态只能是 OPEN / PAID / DROPPED");
        }
        return upper;
    }

    /** 0 或负数视为「没规划」，存 null */
    private Integer normalizePayoff(Integer planned) {
        return planned == null || planned <= 0 ? null : planned;
    }

    private int latestChapterNo(Long novelId) {
        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .select(ChapterEntity::getChapterNo)
                .eq(ChapterEntity::getNovelId, novelId));
        int latest = 0;
        for (ChapterEntity chapter : chapters) {
            if (chapter.getChapterNo() != null && chapter.getChapterNo() > latest) {
                latest = chapter.getChapterNo();
            }
        }
        return latest;
    }

    private List<String> splitLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("\\r?\\n"))
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private ChapterEntity requireChapter(Long chapterId) {
        ChapterEntity chapter = chapterId == null ? null : chapterMapper.selectById(chapterId);
        if (chapter == null) {
            throw BizException.of(ErrorCode.CHAPTER_NOT_FOUND);
        }
        return chapter;
    }

    private void requireNovel(Long novelId) {
        if (novelId == null || novelMapper.selectById(novelId) == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
    }

    private ForeshadowEntity requireForeshadow(Long id) {
        ForeshadowEntity entity = id == null ? null : foreshadowMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "伏笔不存在");
        }
        return entity;
    }
}
