package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.bookshelf.dto.VolumeCreateRequest;
import com.storyforge.modules.bookshelf.dto.VolumeUpdateRequest;
import com.storyforge.modules.bookshelf.dto.VolumeVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.VolumeEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.VolumeMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 卷（分卷）的维护，是章节 {@code volume_no} / {@code volume_title} 的<b>唯一写入方</b>。
 *
 * <p>章节上那两个冗余字段保留着——列表、TXT 导出、管理端目录、拆书预览全都按 {@code volume_no}
 * 分组，保留冗余就不用为了「卷」这件事改一堆查询。代价是一致性只能靠这里保证：
 * 改名必须同步 {@code t_chapter.volume_title}，换序必须同步 {@code t_chapter.volume_no} 与
 * {@code sort_no}。别处一律不许直接写章节的卷字段。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VolumeService {

    private final VolumeMapper volumeMapper;
    private final ChapterMapper chapterMapper;
    private final NovelMapper novelMapper;

    /* ------------------------------------------------------------------ */
    /* 查询                                                               */
    /* ------------------------------------------------------------------ */

    /**
     * 某本书的卷列表，按卷序升序。
     *
     * <p>章数与字数从 {@code t_chapter} 实时聚合，不冗余到卷表——冗余就要在每次保存正文时同步，
     * 而这两个数字只有卷列表用，查一次更省心。</p>
     */
    public List<VolumeVO> list(Long novelId) {
        requireNovel(novelId);
        List<VolumeEntity> volumes = volumesOf(novelId);
        Map<Integer, List<ChapterEntity>> byVolume = new LinkedHashMap<>();
        for (ChapterEntity chapter : chaptersOf(novelId)) {
            int no = chapter.getVolumeNo() == null ? 1 : chapter.getVolumeNo();
            byVolume.computeIfAbsent(no, key -> new ArrayList<>()).add(chapter);
        }
        List<VolumeVO> result = new ArrayList<>();
        for (VolumeEntity volume : volumes) {
            List<ChapterEntity> chapters = byVolume.getOrDefault(volume.getVolumeNo(), List.of());
            int words = chapters.stream()
                    .mapToInt(item -> item.getWordCount() == null ? 0 : item.getWordCount())
                    .sum();
            Integer first = chapters.stream()
                    .map(ChapterEntity::getChapterNo).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            Integer last = chapters.stream()
                    .map(ChapterEntity::getChapterNo).filter(Objects::nonNull)
                    .max(Comparator.naturalOrder()).orElse(null);
            result.add(VolumeVO.of(volume, chapters.size(), words, first, last));
        }
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* 写操作                                                             */
    /* ------------------------------------------------------------------ */

    /** 新增卷：卷序取当前最大 + 1（空书从第 1 卷开始）。 */
    @Transactional(rollbackFor = Exception.class)
    public VolumeVO create(Long novelId, VolumeCreateRequest request) {
        requireNovel(novelId);
        List<VolumeEntity> existing = volumesOf(novelId);
        int nextNo = existing.stream()
                .mapToInt(item -> item.getVolumeNo() == null ? 0 : item.getVolumeNo())
                .max().orElse(0) + 1;
        VolumeEntity entity = new VolumeEntity();
        entity.setUserId(UserContextHolder.getUserIdOrNull());
        entity.setNovelId(novelId);
        entity.setVolumeNo(nextNo);
        entity.setTitle(trimToNull(request == null ? null : request.getTitle()));
        entity.setIntro(request == null ? null : request.getIntro());
        entity.setSortNo(nextNo);
        entity.setDeleted(0);
        entity.setVersion(0);
        volumeMapper.insert(entity);
        log.info("[volume] created novelId={} volumeNo={} title={}", novelId, nextNo, entity.getTitle());
        return one(novelId, entity);
    }

    /**
     * 改卷名 / 卷简介。
     *
     * <p>改名必须把该卷下所有章节的 {@code volume_title} 一起改掉——TXT 导出的卷名行、
     * 管理端目录、拆书预览读的都是章节上的冗余字段。这是一条 UPDATE，不是逐章保存，
     * 因为改卷名不改正文，没必要走章节更新那条会存快照与重算字数的主路径。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public VolumeVO update(Long volumeId, VolumeUpdateRequest request) {
        VolumeEntity volume = requireVolume(volumeId);
        if (request == null) {
            return one(volume.getNovelId(), volume);
        }
        boolean titleGiven = request.getTitle() != null;
        if (titleGiven) {
            volume.setTitle(trimToNull(request.getTitle()));
        }
        if (request.getIntro() != null) {
            volume.setIntro(trimToNull(request.getIntro()));
        }
        volumeMapper.updateById(volume);
        if (titleGiven) {
            syncChapterVolumeTitle(volume.getNovelId(), volume.getVolumeNo(), volume.getTitle());
        }
        log.info("[volume] updated volumeId={} volumeNo={} title={}", volumeId, volume.getVolumeNo(),
                volume.getTitle());
        return one(volume.getNovelId(), volume);
    }

    /**
     * 删除卷。
     *
     * <p>卷下还有章节时必须指定 {@code moveToVolumeNo}：直接删会让那些章节挂在一个
     * 不存在的卷上（列表查不到、导出没有卷名行）。给了目标卷就先把章节迁过去再删，
     * 章号保持不变。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long volumeId, Integer moveToVolumeNo) {
        VolumeEntity volume = requireVolume(volumeId);
        Long novelId = volume.getNovelId();
        List<ChapterEntity> chapters = chaptersOf(novelId).stream()
                .filter(item -> volume.getVolumeNo().equals(
                        item.getVolumeNo() == null ? 1 : item.getVolumeNo()))
                .toList();
        if (!chapters.isEmpty()) {
            if (moveToVolumeNo == null) {
                throw BizException.of(ErrorCode.VOLUME_HAS_CHAPTERS,
                        "这一卷下还有 " + chapters.size() + " 章，请先选择要迁移到哪一卷");
            }
            if (moveToVolumeNo.equals(volume.getVolumeNo())) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "不能把章节迁移到正要删除的这一卷");
            }
            VolumeEntity target = volumesOf(novelId).stream()
                    .filter(item -> item.getVolumeNo().equals(moveToVolumeNo))
                    .findFirst()
                    .orElseThrow(() -> BizException.of(ErrorCode.VOLUME_TARGET_INVALID));
            moveChapters(chapters, target);
        }
        volumeMapper.deleteById(volumeId);
        renumber(novelId);
        log.info("[volume] deleted volumeId={} volumeNo={} movedChapters={} to={}",
                volumeId, volume.getVolumeNo(), chapters.size(), moveToVolumeNo);
    }

    /**
     * 与相邻卷换序。
     *
     * <p>换的是 {@code volume_no} 本身（不是只换 {@code sort_no}）：卷序是作者理解的
     * 「第一卷 / 第二卷」，也是 {@code sort_no = volume_no * 1000 + chapter_no} 的组成部分。
     * 两侧章节的 {@code volume_no} 与 {@code sort_no} 一起重算。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public List<VolumeVO> move(Long volumeId, String direction) {
        VolumeEntity volume = requireVolume(volumeId);
        Long novelId = volume.getNovelId();
        List<VolumeEntity> volumes = volumesOf(novelId);
        boolean up = "UP".equalsIgnoreCase(direction);
        if (!up && !"DOWN".equalsIgnoreCase(direction)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "移动方向只能是 UP 或 DOWN");
        }
        VolumeEntity neighbor = null;
        for (VolumeEntity item : volumes) {
            int delta = item.getVolumeNo() - volume.getVolumeNo();
            if (up && delta < 0 && (neighbor == null || item.getVolumeNo() > neighbor.getVolumeNo())) {
                neighbor = item;
            }
            if (!up && delta > 0 && (neighbor == null || item.getVolumeNo() < neighbor.getVolumeNo())) {
                neighbor = item;
            }
        }
        if (neighbor == null) {
            throw BizException.of(ErrorCode.VOLUME_MOVE_INVALID);
        }
        Integer targetNo = volume.getVolumeNo();
        Integer selfNo = neighbor.getVolumeNo();
        // 卷序上有唯一索引，互换必须借一个空位：park 取当前最大卷号 + 1，一定没人占
        int park = volumes.stream()
                .mapToInt(item -> item.getVolumeNo() == null ? 0 : item.getVolumeNo())
                .max().orElse(0) + 1;

        volume.setVolumeNo(park);
        volume.setSortNo(park);
        volumeMapper.updateById(volume);
        neighbor.setVolumeNo(targetNo);
        neighbor.setSortNo(targetNo);
        volumeMapper.updateById(neighbor);
        volume.setVolumeNo(selfNo);
        volume.setSortNo(selfNo);
        volumeMapper.updateById(volume);

        // 章节同理：直接对调会让两侧章节混在同一个卷号里，所以也借一次 park
        syncChapterVolume(novelId, targetNo, volume.getTitle(), park);
        syncChapterVolume(novelId, selfNo, neighbor.getTitle(), targetNo);
        syncChapterVolume(novelId, park, volume.getTitle(), selfNo);
        log.info("[volume] moved novelId={} {} <-> {}", novelId, targetNo, selfNo);
        return list(novelId);
    }

    /* ------------------------------------------------------------------ */
    /* 给建章链路用的 upsert                                              */
    /* ------------------------------------------------------------------ */

    /**
     * 确保某本书的某一卷存在。
     *
     * <p>所有会写章节卷字段的链路（手动建章、AI 生成大纲、拆书建书、JSON 导入、
     * 智能助手建章）都要先过这一关，否则卷表是空的，卷列表就查不到东西。</p>
     *
     * <p>{@code title} 为空时不覆盖已有卷名：老数据里章节的卷名可能是空的，
     * 不能因为建了一章没写卷名就把卷名清掉。</p>
     *
     * @return 这一卷（已有或新建的）；调用方拿它的卷名去对齐章节的冗余字段
     */
    public VolumeEntity ensureVolume(Long novelId, Integer volumeNo, String title) {
        int no = volumeNo == null || volumeNo < 1 ? 1 : volumeNo;
        VolumeEntity existing = volumesOf(novelId).stream()
                .filter(item -> item.getVolumeNo() != null && item.getVolumeNo() == no)
                .findFirst()
                .orElse(null);
        if (existing != null) {
            String trimmed = trimToNull(title);
            if (trimmed != null && !trimmed.equals(existing.getTitle())) {
                existing.setTitle(trimmed);
                volumeMapper.updateById(existing);
            }
            return existing;
        }
        VolumeEntity entity = new VolumeEntity();
        entity.setUserId(UserContextHolder.getUserIdOrNull());
        entity.setNovelId(novelId);
        entity.setVolumeNo(no);
        entity.setTitle(trimToNull(title));
        entity.setSortNo(no);
        entity.setDeleted(0);
        entity.setVersion(0);
        volumeMapper.insert(entity);
        return entity;
    }

    /** 删书时级联清理卷记录。 */
    public int deleteByNovel(Long novelId) {
        return volumeMapper.delete(Wrappers.<VolumeEntity>lambdaQuery()
                .eq(VolumeEntity::getNovelId, novelId));
    }

    /* ------------------------------------------------------------------ */
    /* 启动回填                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 为「有章节但卷表是空的」小说补卷记录。
     *
     * <p>迁移只建表不回填：回填要生成雪花 ID，SQL 里造不出来（三库都得一致）。
     * 放在应用启动后跑，幂等——每次只补缺失的那些 (novel_id, volume_no) 组合，
     * 反复启动不会重复插入。卷名取该卷里第一个非空的 {@code volume_title}。</p>
     *
     * @return 本次补了几条
     */
    @Transactional(rollbackFor = Exception.class)
    public int backfill() {
        List<VolumeEntity> all = volumeMapper.selectList(Wrappers.<VolumeEntity>lambdaQuery());
        Map<String, VolumeEntity> known = new LinkedHashMap<>();
        for (VolumeEntity item : all) {
            known.put(key(item.getNovelId(), item.getVolumeNo()), item);
        }
        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery());
        Map<String, ChapterEntity> sample = new LinkedHashMap<>();
        for (ChapterEntity chapter : chapters) {
            String key = key(chapter.getNovelId(),
                    chapter.getVolumeNo() == null ? 1 : chapter.getVolumeNo());
            // 卷名取该卷里第一个非空的：老数据可能只有部分章节写了卷名
            ChapterEntity previous = sample.get(key);
            boolean better = previous == null
                    || (previous.getVolumeTitle() == null && chapter.getVolumeTitle() != null);
            if (better) {
                sample.put(key, chapter);
            }
        }
        int created = 0;
        for (Map.Entry<String, ChapterEntity> entry : sample.entrySet()) {
            if (known.containsKey(entry.getKey())) {
                continue;
            }
            ChapterEntity chapter = entry.getValue();
            VolumeEntity entity = new VolumeEntity();
            entity.setUserId(chapter.getUserId());
            entity.setNovelId(chapter.getNovelId());
            entity.setVolumeNo(chapter.getVolumeNo() == null ? 1 : chapter.getVolumeNo());
            entity.setTitle(trimToNull(chapter.getVolumeTitle()));
            entity.setSortNo(entity.getVolumeNo());
            entity.setDeleted(0);
            entity.setVersion(0);
            volumeMapper.insert(entity);
            created++;
        }
        if (created > 0) {
            log.info("[volume] backfilled {} volume rows from t_chapter", created);
        }
        return created;
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                               */
    /* ------------------------------------------------------------------ */

    /** 把一批章节迁到目标卷：改 volume_no 与 volume_title，并重算 sort_no。 */
    private void moveChapters(List<ChapterEntity> chapters, VolumeEntity target) {
        for (ChapterEntity chapter : chapters) {
            chapter.setVolumeNo(target.getVolumeNo());
            chapter.setVolumeTitle(trimToNull(target.getTitle()));
            chapter.setSortNo(target.getVolumeNo() * 1000 + chapter.getChapterNo());
            chapterMapper.updateById(chapter);
        }
    }

    /** 改卷名后同步该卷下章节的冗余卷名。 */
    private void syncChapterVolumeTitle(Long novelId, Integer volumeNo, String title) {
        String trimmed = trimToNull(title);
        chapterMapper.update(null, Wrappers.<ChapterEntity>lambdaUpdate()
                .eq(ChapterEntity::getNovelId, novelId)
                .eq(ChapterEntity::getVolumeNo, volumeNo)
                .set(ChapterEntity::getVolumeTitle, trimmed));
    }

    /** 换序时同步某一卷的卷序与卷名到章节（fromNo → toNo）。 */
    private void syncChapterVolume(Long novelId, Integer fromNo, String title, Integer toNo) {
        List<ChapterEntity> chapters = chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .eq(ChapterEntity::getVolumeNo, fromNo));
        for (ChapterEntity chapter : chapters) {
            chapter.setVolumeNo(toNo);
            chapter.setVolumeTitle(trimToNull(title));
            chapter.setSortNo(toNo * 1000 + chapter.getChapterNo());
            chapterMapper.updateById(chapter);
        }
    }

    /**
     * 把卷序重排成 1..N 连续。
     *
     * <p>删卷之后会留洞（1、3），而 {@code sort_no} 是用卷号算的，洞不影响排序，
     * 但「第一卷 / 第二卷」的编号会跳，导出时也跟着跳。删除是低频动作，直接重排。</p>
     */
    private void renumber(Long novelId) {
        List<VolumeEntity> volumes = volumesOf(novelId);
        int no = 1;
        for (VolumeEntity volume : volumes) {
            Integer old = volume.getVolumeNo();
            if (!Objects.equals(old, no)) {
                // 往前补洞：目标号此刻一定是空的（比当前号小，且前面的卷已经排好了），直接写
                syncChapterVolume(novelId, old, volume.getTitle(), no);
                volume.setVolumeNo(no);
                volume.setSortNo(no);
                volumeMapper.updateById(volume);
            }
            no++;
        }
    }

    private VolumeVO one(Long novelId, VolumeEntity volume) {
        List<ChapterEntity> chapters = chaptersOf(novelId).stream()
                .filter(item -> volume.getVolumeNo().equals(
                        item.getVolumeNo() == null ? 1 : item.getVolumeNo()))
                .toList();
        int words = chapters.stream()
                .mapToInt(item -> item.getWordCount() == null ? 0 : item.getWordCount())
                .sum();
        Integer first = chapters.stream()
                .map(ChapterEntity::getChapterNo).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        Integer last = chapters.stream()
                .map(ChapterEntity::getChapterNo).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null);
        return VolumeVO.of(volume, chapters.size(), words, first, last);
    }

    private List<VolumeEntity> volumesOf(Long novelId) {
        return volumeMapper.selectList(Wrappers.<VolumeEntity>lambdaQuery()
                .eq(VolumeEntity::getNovelId, novelId)
                .orderByAsc(VolumeEntity::getSortNo));
    }

    private List<ChapterEntity> chaptersOf(Long novelId) {
        return chapterMapper.selectList(Wrappers.<ChapterEntity>lambdaQuery()
                .eq(ChapterEntity::getNovelId, novelId)
                .orderByAsc(ChapterEntity::getSortNo));
    }

    private VolumeEntity requireVolume(Long volumeId) {
        if (volumeId == null) {
            throw BizException.of(ErrorCode.VOLUME_NOT_FOUND);
        }
        VolumeEntity volume = volumeMapper.selectById(volumeId);
        if (volume == null) {
            throw BizException.of(ErrorCode.VOLUME_NOT_FOUND);
        }
        return volume;
    }

    private NovelEntity requireNovel(Long novelId) {
        NovelEntity novel = novelId == null ? null : novelMapper.selectById(novelId);
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        return novel;
    }

    private String key(Long novelId, Integer volumeNo) {
        return novelId + ":" + volumeNo;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
