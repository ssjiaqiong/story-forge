package com.storyforge.modules.novel.service;

import com.storyforge.common.api.PageResult;
import com.storyforge.modules.novel.dto.AdminChapterVO;
import com.storyforge.modules.novel.dto.AdminNovelDetailVO;
import com.storyforge.modules.novel.dto.AdminNovelQuery;
import com.storyforge.modules.novel.dto.AdminNovelVO;
import com.storyforge.modules.novel.dto.AdminPurgeResultVO;

import java.util.List;

/**
 * 超管「小说管理」服务：跨用户查看小说的基本信息、目录与正文。
 *
 * <p>查看类接口全部只读，绝不修改用户创作内容；唯一的写操作是
 * {@link #purge}/{@link #purgeBatch}：把用户已经<b>逻辑删除</b>的小说彻底清除，
 * 属于平台数据治理动作，且默认无法恢复。</p>
 */
public interface AdminNovelService {

    /** 全部用户的小说分页。 */
    PageResult<AdminNovelVO> page(AdminNovelQuery query);

    /** 小说详情 + 章节目录（不含正文）。 */
    AdminNovelDetailVO detail(Long novelId);

    /** 单章正文。 */
    AdminChapterVO chapter(Long novelId, Long chapterId);

    /** 物理删除一本已逻辑删除的小说及其全部关联数据。 */
    AdminPurgeResultVO purge(Long novelId);

    /** 批量物理删除；单本失败不影响其余，逐本返回结果。 */
    List<AdminPurgeResultVO> purgeBatch(List<Long> novelIds);
}
