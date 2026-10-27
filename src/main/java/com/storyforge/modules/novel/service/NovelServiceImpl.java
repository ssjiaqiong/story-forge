package com.storyforge.modules.novel.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.novel.dto.NovelDraft;
import com.storyforge.modules.novel.dto.NovelPatch;
import com.storyforge.modules.novel.dto.NovelQuery;
import com.storyforge.modules.novel.dto.NovelCoverVO;
import com.storyforge.modules.novel.dto.NovelVO;
import com.storyforge.modules.novel.entity.NovelCoverEntity;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelCoverMapper;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 小说服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelServiceImpl implements NovelService {

    private static final int MAX_TITLE_LENGTH = 200;
    private static final int TITLE_DEDUPE_LIMIT = 50;
    private static final java.util.Set<String> STATUSES =
            java.util.Set.of("DRAFT", "WRITING", "FINISHED", "ARCHIVED");

    private final NovelMapper novelMapper;
    private final NovelCoverMapper coverMapper;
    private final ObjectMapper objectMapper;

    @Override
    public PageResult<NovelVO> page(NovelQuery query) {
        NovelQuery condition = query == null ? new NovelQuery() : query;
        Page<NovelEntity> page = new Page<>(condition.normalizedPage(), condition.normalizedSize());

        var wrapper = Wrappers.<NovelEntity>lambdaQuery()
                .orderByDesc(NovelEntity::getCreatedAt);
        if (hasText(condition.getKeyword())) {
            String keyword = condition.getKeyword().trim();
            wrapper.and(w -> w.like(NovelEntity::getTitle, keyword)
                    .or()
                    .like(NovelEntity::getIntro, keyword));
        }
        if (hasText(condition.getStatus())) {
            wrapper.eq(NovelEntity::getStatus, condition.getStatus().trim().toUpperCase());
        }

        Page<NovelEntity> result = novelMapper.selectPage(page, wrapper);
        List<NovelVO> records = toVOList(result.getRecords());
        return PageResult.of(records, result.getTotal(), result.getCurrent(), result.getSize());
    }

    @Override
    public NovelVO detail(Long id) {
        NovelEntity entity = novelMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        NovelVO vo = toVO(entity);
        vo.setCovers(selectCovers(List.of(id)).getOrDefault(id, List.of()));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public NovelVO createDraft(NovelDraft draft) {
        Long userId = UserContextHolder.requireUserId();
        String title = uniqueTitle(draft.title());

        NovelEntity entity = new NovelEntity();
        entity.setUserId(userId);
        entity.setTitle(title);
        entity.setPenName(blankToNull(draft.penName()));
        entity.setGenre(blankToNull(draft.genre()));
        entity.setTags(writeTags(draft.tags()));
        entity.setIntro(blankToNull(draft.intro()));
        entity.setStatus("DRAFT");
        entity.setSource(hasText(draft.source()) ? draft.source() : "MANUAL");
        entity.setInspirationSessionId(draft.inspirationSessionId());
        entity.setChapterCount(0);
        entity.setWordCount(0);
        entity.setPlannedWords(0);
        entity.setDeleted(0);
        entity.setVersion(0);
        novelMapper.insert(entity);

        log.info("[novel] created id={} userId={} title={} source={}",
                entity.getId(), userId, title, entity.getSource());
        return toVO(entity);
    }

    /* ------------------------------------------------------------------ */

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateConstraint(Long novelId, String constraintText) {
        if (novelId == null) {
            return;
        }
        NovelEntity patch = new NovelEntity();
        patch.setId(novelId);
        patch.setConstraintText(constraintText);
        novelMapper.updateById(patch);
    }

    @Override
    public NovelVO update(Long id, NovelPatch patch) {
        NovelEntity existing = novelMapper.selectById(id);
        if (existing == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        if (patch == null) {
            return toVO(existing);
        }

        var update = Wrappers.<NovelEntity>lambdaUpdate()
                .eq(NovelEntity::getId, id)
                .set(NovelEntity::getUpdatedAt, java.time.LocalDateTime.now())
                .setSql("version = version + 1");
        boolean changed = false;

        if (patch.title() != null) {
            String title = patch.title().trim();
            if (title.isEmpty()) {
                throw BizException.of(ErrorCode.NOVEL_TITLE_EMPTY);
            }
            if (title.length() > MAX_TITLE_LENGTH) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "书名最长 200 个字符");
            }
            if (!title.equals(existing.getTitle())) {
                long duplicated = novelMapper.selectCount(Wrappers.<NovelEntity>lambdaQuery()
                        .eq(NovelEntity::getTitle, title)
                        .ne(NovelEntity::getId, id));
                if (duplicated > 0L) {
                    throw BizException.of(ErrorCode.NOVEL_TITLE_DUPLICATED);
                }
            }
            update.set(NovelEntity::getTitle, title);
            changed = true;
        }
        if (patch.penName() != null) {
            update.set(NovelEntity::getPenName, blankToNull(patch.penName()));
            changed = true;
        }
        if (patch.genre() != null) {
            update.set(NovelEntity::getGenre, blankToNull(patch.genre()));
            changed = true;
        }
        if (patch.tags() != null) {
            // 空数组写入 null，实现「清空标签」
            update.set(NovelEntity::getTags, writeTags(patch.tags()));
            changed = true;
        }
        if (patch.intro() != null) {
            update.set(NovelEntity::getIntro, blankToNull(patch.intro()));
            changed = true;
        }
        if (patch.status() != null) {
            String status = patch.status().trim().toUpperCase();
            if (!STATUSES.contains(status)) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "小说状态非法，可选 DRAFT / WRITING / FINISHED / ARCHIVED");
            }
            update.set(NovelEntity::getStatus, status);
            changed = true;
        }
        if (patch.plannedWords() != null) {
            int planned = patch.plannedWords();
            if (planned < 0) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "预计完结字数不能为负数");
            }
            if (planned > 0 && planned < 10000) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "预计完结字数至少 1 万字；填 0 表示不设置");
            }
            update.set(NovelEntity::getPlannedWords, planned);
            changed = true;
        }

        if (!changed) {
            return toVO(existing);
        }
        novelMapper.update(null, update);
        log.info("[novel] updated id={} userId={}", id, existing.getUserId());
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        NovelEntity existing = novelMapper.selectById(id);
        if (existing == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        novelMapper.deleteById(id);
        log.info("[novel] deleted id={} userId={}", id, existing.getUserId());
    }

    /* ------------------------------------------------------------------ */

    /** 书名在同一用户内唯一；重名时自动追加序号，保证一键创建不失败。 */
    private String uniqueTitle(String raw) {
        String base = hasText(raw) ? raw.trim() : "未命名小说";
        if (base.length() > MAX_TITLE_LENGTH - 8) {
            base = base.substring(0, MAX_TITLE_LENGTH - 8);
        }
        String candidate = base;
        for (int i = 2; i <= TITLE_DEDUPE_LIMIT; i++) {
            long exists = novelMapper.selectCount(Wrappers.<NovelEntity>lambdaQuery()
                    .eq(NovelEntity::getTitle, candidate));
            if (exists == 0L) {
                return candidate;
            }
            candidate = base + "（" + i + "）";
        }
        return base + "（" + System.currentTimeMillis() % 100000 + "）";
    }

    /**
     * 批量转 VO 并补封面。
     *
     * <p>封面单独一次 {@code IN} 查询，避免书架列表逐本查（N+1）。</p>
     */
    private List<NovelVO> toVOList(List<NovelEntity> entities) {
        if (entities == null || entities.isEmpty()) {
            return new ArrayList<>();
        }
        List<NovelVO> vos = entities.stream().map(this::toVO).toList();
        Map<Long, List<NovelCoverVO>> covers = selectCovers(entities.stream()
                .map(NovelEntity::getId).toList());
        vos.forEach(vo -> vo.setCovers(covers.getOrDefault(vo.getId(), List.of())));
        return vos;
    }

    /** 取指定小说的封面：主封面在前，其余按切换顺序。 */
    private Map<Long, List<NovelCoverVO>> selectCovers(List<Long> novelIds) {
        if (novelIds == null || novelIds.isEmpty()) {
            return Map.of();
        }
        List<NovelCoverEntity> entities = coverMapper.selectList(Wrappers.<NovelCoverEntity>lambdaQuery()
                .in(NovelCoverEntity::getNovelId, novelIds)
                .orderByDesc(NovelCoverEntity::getIsPrimary)
                .orderByAsc(NovelCoverEntity::getSortNo));
        Map<Long, List<NovelCoverVO>> grouped = new LinkedHashMap<>();
        for (NovelCoverEntity entity : entities) {
            grouped.computeIfAbsent(entity.getNovelId(), key -> new ArrayList<>())
                    .add(NovelCoverVO.from(entity));
        }
        return grouped;
    }

    private NovelVO toVO(NovelEntity entity) {
        NovelVO vo = new NovelVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setPenName(entity.getPenName());
        vo.setGenre(entity.getGenre());
        vo.setTags(readTags(entity.getTags()));
        vo.setIntro(entity.getIntro());
        vo.setStatus(entity.getStatus());
        vo.setSource(entity.getSource());
        vo.setInspirationSessionId(entity.getInspirationSessionId());
        vo.setChapterCount(entity.getChapterCount());
        vo.setWordCount(entity.getWordCount());
        vo.setConstraintText(entity.getConstraintText());
        vo.setPlannedWords(entity.getPlannedWords() == null ? 0 : entity.getPlannedWords());
        vo.setAutoWriting(false);
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private String writeTags(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(tags.stream()
                    .filter(this::hasText)
                    .map(String::trim)
                    .distinct()
                    .limit(8)
                    .toList());
        } catch (Exception ex) {
            log.warn("[novel] serialize tags failed: {}", ex.getMessage());
            return null;
        }
    }

    private List<String> readTags(String raw) {
        if (!hasText(raw)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String blankToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
