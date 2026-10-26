package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.exception.BizException;
import com.storyforge.common.util.WordCounts;
import com.storyforge.modules.bookshelf.dto.ChapterVersionDetailVO;
import com.storyforge.modules.bookshelf.dto.ChapterVersionVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterVersionEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterVersionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 章节历史版本的存取与保留策略。
 *
 * <p>快照语义是「保存前的旧内容」，所以这里所有写操作都只发生在
 * {@code updateChapter} 之前的那一刻，读操作面向历史列表与回滚。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterVersionServiceImpl implements ChapterVersionService {

    private final ChapterVersionMapper versionMapper;
    private final JdbcTemplate jdbcTemplate;

    /** 非自动保存版本每章保留条数 */
    @Value("${storyforge.version.keep:20}")
    private int keep;

    /** 自动保存版本每章保留条数 */
    @Value("${storyforge.version.auto-keep:5}")
    private int autoKeep;

    /** 自动保存版本保留天数 */
    @Value("${storyforge.version.auto-days:7}")
    private int autoDays;

    /** 逻辑删除行超过这个数才做一次物理清理，避免每次保存都扫表 */
    @Value("${storyforge.version.purge-threshold:100}")
    private int purgeThreshold;

    /**
     * 存快照。
     *
     * <p>调用方已保证「正文确实要变」，这里再做两道过滤：没有旧正文（新章第一次写入）、
     * 或旧正文与最近一版完全一致（反复保存同一份内容）时都不产生新版本。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.MANDATORY)
    public void snapshot(ChapterEntity before, String source, String note) {
        if (before == null || before.getId() == null) {
            return;
        }
        String content = before.getContent();
        if (content == null || content.isBlank()) {
            return;
        }
        ChapterVersionEntity latest = latestOf(before.getId());
        if (latest != null && content.equals(latest.getContent())) {
            return;
        }
        ChapterVersionEntity entity = new ChapterVersionEntity();
        entity.setUserId(before.getUserId());
        entity.setNovelId(before.getNovelId());
        entity.setChapterId(before.getId());
        entity.setVersionNo(latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1);
        entity.setSource(source == null ? ChapterVersionEntity.SOURCE_MANUAL : source);
        entity.setTitle(before.getTitle());
        entity.setContent(content);
        entity.setWordCount(before.getWordCount() == null || before.getWordCount() <= 0
                ? WordCounts.countChinese(content) : before.getWordCount());
        entity.setNote(hasText(note) ? truncate(note, 200) : defaultNote(entity.getSource()));
        entity.setDeleted(0);
        entity.setVersion(0);
        versionMapper.insert(entity);
        prune(before.getId(), before.getUserId());
    }

    @Override
    public PageResult<ChapterVersionVO> list(Long chapterId, long page, long size) {
        long currentPage = page < 1 ? 1 : page;
        long pageSize = size < 1 ? 20 : Math.min(size, 100);
        long total = versionMapper.selectCount(Wrappers.<ChapterVersionEntity>lambdaQuery()
                .eq(ChapterVersionEntity::getChapterId, chapterId));
        List<ChapterVersionEntity> rows = versionMapper.selectList(Wrappers.<ChapterVersionEntity>lambdaQuery()
                .eq(ChapterVersionEntity::getChapterId, chapterId)
                .orderByDesc(ChapterVersionEntity::getVersionNo)
                .last("limit " + pageSize + " offset " + (currentPage - 1) * pageSize));
        List<ChapterVersionVO> records = rows.stream().map(this::toVO).toList();
        return PageResult.of(records, total, currentPage, pageSize);
    }

    @Override
    public ChapterVersionDetailVO detail(Long chapterId, Long versionId) {
        ChapterVersionEntity entity = require(chapterId, versionId);
        return new ChapterVersionDetailVO(entity.getId(), entity.getVersionNo(), entity.getSource(),
                label(entity.getSource()), entity.getTitle(), entity.getContent(),
                entity.getWordCount(), entity.getNote(), entity.getCreatedAt());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long chapterId, Long versionId) {
        ChapterVersionEntity entity = require(chapterId, versionId);
        versionMapper.deleteById(entity.getId());
        log.info("[chapter-version] deleted chapterId={} versionNo={}", chapterId, entity.getVersionNo());
    }

    @Override
    public long count(Long chapterId) {
        return versionMapper.selectCount(Wrappers.<ChapterVersionEntity>lambdaQuery()
                .eq(ChapterVersionEntity::getChapterId, chapterId));
    }

    @Override
    public LocalDateTime latestAutoAt(Long chapterId) {
        List<ChapterVersionEntity> rows = versionMapper.selectList(Wrappers.<ChapterVersionEntity>lambdaQuery()
                .eq(ChapterVersionEntity::getChapterId, chapterId)
                .eq(ChapterVersionEntity::getSource, ChapterVersionEntity.SOURCE_AUTO)
                .orderByDesc(ChapterVersionEntity::getVersionNo)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0).getCreatedAt();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteByChapter(Long chapterId) {
        versionMapper.delete(Wrappers.<ChapterVersionEntity>lambdaQuery()
                .eq(ChapterVersionEntity::getChapterId, chapterId));
    }

    @Override
    public ChapterVersionEntity require(Long chapterId, Long versionId) {
        if (versionId == null) {
            throw BizException.of(ErrorCode.CHAPTER_VERSION_NOT_FOUND);
        }
        ChapterVersionEntity entity = versionMapper.selectById(versionId);
        if (entity == null || !chapterId.equals(entity.getChapterId())) {
            throw BizException.of(ErrorCode.CHAPTER_VERSION_NOT_FOUND);
        }
        return entity;
    }

    /* ------------------------------------------------------------------ */

    private ChapterVersionEntity latestOf(Long chapterId) {
        List<ChapterVersionEntity> rows = versionMapper.selectList(Wrappers.<ChapterVersionEntity>lambdaQuery()
                .eq(ChapterVersionEntity::getChapterId, chapterId)
                .orderByDesc(ChapterVersionEntity::getVersionNo)
                .last("limit 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 保留策略：AUTO 单独一组（条数 + 天数双限），其余按来源合并计 20 条。
     *
     * <p>按 version_no 倒序遍历，超出的逻辑删除；逻辑删行堆积超过阈值时物理清一次。</p>
     */
    private void prune(Long chapterId, Long userId) {
        List<ChapterVersionEntity> rows = versionMapper.selectList(Wrappers.<ChapterVersionEntity>lambdaQuery()
                .eq(ChapterVersionEntity::getChapterId, chapterId)
                .orderByDesc(ChapterVersionEntity::getVersionNo));
        LocalDateTime autoDeadline = LocalDateTime.now().minusDays(Math.max(autoDays, 1));
        int autoKept = 0;
        int otherKept = 0;
        int removed = 0;
        for (ChapterVersionEntity row : rows) {
            boolean isAuto = ChapterVersionEntity.SOURCE_AUTO.equals(row.getSource());
            boolean drop;
            if (isAuto) {
                boolean tooOld = row.getCreatedAt() != null && row.getCreatedAt().isBefore(autoDeadline);
                drop = tooOld || autoKept >= Math.max(autoKeep, 1);
                if (!drop) {
                    autoKept++;
                }
            } else {
                drop = otherKept >= Math.max(keep, 1);
                if (!drop) {
                    otherKept++;
                }
            }
            if (drop) {
                versionMapper.deleteById(row.getId());
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("[chapter-version] pruned chapterId={} removed={}", chapterId, removed);
        }
        purgeIfNeeded(chapterId, userId);
    }

    /**
     * 把已经逻辑删除的老行物理清掉。
     *
     * <p>走 JdbcTemplate 而不是 Mapper：{@code @TableLogic} 会把 delete 改写成 update，
     * 真正要删数据只能绕开它；顺带避免数据权限插件改写这条 SQL。</p>
     */
    private void purgeIfNeeded(Long chapterId, Long userId) {
        if (userId == null) {
            return;
        }
        Integer deleted;
        try {
            deleted = jdbcTemplate.queryForObject(
                    "select count(*) from t_chapter_version where user_id = ? and chapter_id = ? and deleted = 1",
                    Integer.class, userId, chapterId);
        } catch (RuntimeException ex) {
            log.warn("[chapter-version] count deleted failed chapterId={}: {}", chapterId, ex.getMessage());
            return;
        }
        if (deleted == null || deleted <= purgeThreshold) {
            return;
        }
        jdbcTemplate.update(
                "delete from t_chapter_version where user_id = ? and chapter_id = ? and deleted = 1 "
                        + "and id not in (select id from (select id from t_chapter_version "
                        + "where user_id = ? and chapter_id = ? and deleted = 1 "
                        + "order by version_no desc limit ?) keep_rows)",
                userId, chapterId, userId, chapterId, purgeThreshold);
        log.info("[chapter-version] purged chapterId={} deletedRows>{}", chapterId, purgeThreshold);
    }

    private ChapterVersionVO toVO(ChapterVersionEntity entity) {
        return new ChapterVersionVO(entity.getId(), entity.getVersionNo(), entity.getSource(),
                label(entity.getSource()), entity.getTitle(), entity.getWordCount(),
                entity.getNote(), entity.getCreatedAt());
    }

    private String defaultNote(String source) {
        return switch (source) {
            case ChapterVersionEntity.SOURCE_AI_BODY -> "AI 写正文 · 覆盖前留档";
            case ChapterVersionEntity.SOURCE_AI_REVISE -> "按分析修改 · 覆盖前留档";
            case ChapterVersionEntity.SOURCE_BATCH -> "批量连写 · 覆盖前留档";
            case ChapterVersionEntity.SOURCE_RESTORE -> "回滚前自动留档";
            case ChapterVersionEntity.SOURCE_IMPORT -> "导入覆盖 · 留档";
            case ChapterVersionEntity.SOURCE_AUTO -> "自动保存 · 覆盖前留档";
            default -> "手动保存 · 覆盖前留档";
        };
    }

    /** 来源中文名，前端直接用，避免两边各维护一份映射。 */
    private String label(String source) {
        if (source == null) {
            return "未知";
        }
        return switch (source) {
            case ChapterVersionEntity.SOURCE_AI_BODY -> "AI 写正文";
            case ChapterVersionEntity.SOURCE_AI_REVISE -> "按分析修改";
            case ChapterVersionEntity.SOURCE_BATCH -> "批量连写";
            case ChapterVersionEntity.SOURCE_RESTORE -> "回滚";
            case ChapterVersionEntity.SOURCE_IMPORT -> "导入";
            case ChapterVersionEntity.SOURCE_AUTO -> "自动保存";
            default -> "手动保存";
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
