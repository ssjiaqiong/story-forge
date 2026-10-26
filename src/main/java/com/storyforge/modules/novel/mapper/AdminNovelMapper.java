package com.storyforge.modules.novel.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.storyforge.modules.novel.dto.AdminChapterVO;
import com.storyforge.modules.novel.dto.AdminNovelQuery;
import com.storyforge.modules.novel.dto.AdminNovelVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 超管小说管理只读查询。
 *
 * <p>这类查询要跨用户读取，调用方必须先用
 * {@code DataPermissionContext.withoutIsolation(...)} 放行隔离；SQL 见
 * {@code resources/mapper/AdminNovelMapper.xml}。</p>
 */
@Mapper
public interface AdminNovelMapper {

    /** 全部用户的小说分页（可按书名/账号、状态、创建时间筛选）。{@code deletedFlag} 为 null 时不限删除标记。 */
    IPage<AdminNovelVO> selectAdminNovelPage(IPage<AdminNovelVO> page,
                                             @Param("q") AdminNovelQuery query,
                                             @Param("statuses") List<String> statuses,
                                             @Param("deletedFlag") Integer deletedFlag);

    /** 单本小说基本信息（不校验归属）。 */
    AdminNovelVO selectAdminNovelById(@Param("novelId") Long novelId);

    /** 章节目录，不含正文。{@code includeDeleted=true} 时连逻辑删除的章节一起返回（小说已被删除时用）。 */
    List<AdminChapterVO> selectAdminChapters(@Param("novelId") Long novelId,
                                             @Param("includeDeleted") boolean includeDeleted);

    /** 单章正文。 */
    AdminChapterVO selectAdminChapter(@Param("novelId") Long novelId,
                                      @Param("chapterId") Long chapterId);

    /**
     * 物理删除某部小说在指定表里的全部行（不过滤 deleted，逻辑删除的也一并清掉）。
     *
     * <p>{@code table} 只能用 {@code AdminNovelServiceImpl.NOVEL_CHILD_TABLES} 里的常量，
     * 该列表是代码内白名单，绝不接受外部输入，避免 SQL 注入。</p>
     */
    int deleteByNovelId(@Param("table") String table, @Param("novelId") Long novelId);

    /** 物理删除小说主表行。 */
    int deleteNovelById(@Param("novelId") Long novelId);

    /**
     * 解除拆书记录对这本小说的引用（不删除记录本身，保留用户的拆书历史）。
     *
     * <p>否则拆书弹窗里的「打开工作台」会指向一本已经不存在的书。</p>
     */
    int clearBookDigestNovel(@Param("novelId") Long novelId);
}
