package com.storyforge.modules.bookshelf.service;

import com.storyforge.common.api.PageResult;
import com.storyforge.modules.bookshelf.dto.ChapterVersionDetailVO;
import com.storyforge.modules.bookshelf.dto.ChapterVersionVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterVersionEntity;

/**
 * 章节正文的历史版本。
 *
 * <p>只负责「存快照、读快照、删快照」，不碰 {@code t_chapter}——回滚要改写正文，
 * 由 {@link NovelAssetService#restoreVersion} 统一走既有的更新路径完成，
 * 这样版本服务不会被章节服务反向依赖，避免循环引用。</p>
 */
public interface ChapterVersionService {

    /**
     * 把章节当前内容存为一版。
     *
     * @param before 变更前的章节实体（从库里读出来的那份）
     * @param source 触发来源，见 {@link ChapterVersionEntity} 的常量
     * @param note   备注；为空时按来源给默认文案
     */
    void snapshot(ChapterEntity before, String source, String note);

    PageResult<ChapterVersionVO> list(Long chapterId, long page, long size);

    /** 单版全文；越权或不存在抛 {@code 40017}。 */
    ChapterVersionDetailVO detail(Long chapterId, Long versionId);

    /** 逻辑删除单版，不影响当前正文。 */
    void delete(Long chapterId, Long versionId);

    /** 章节下的历史版本条数，前端用来显示角标。 */
    long count(Long chapterId);

    /**
     * 该章最近一条「自动保存」版本的创建时间；没有则返回 null。
     *
     * <p>自动保存的节流靠它：距上一版不足 N 分钟就不再写快照，
     * 否则一章编辑十分钟能刷出几十条垃圾版本。</p>
     */
    java.time.LocalDateTime latestAutoAt(Long chapterId);

    /** 章节删除时一并清掉它的历史版本，避免留下指向空章的数据。 */
    void deleteByChapter(Long chapterId);

    /** 取版本实体（回滚用），不存在或不属于该章抛 {@code 40017}。 */
    ChapterVersionEntity require(Long chapterId, Long versionId);
}
