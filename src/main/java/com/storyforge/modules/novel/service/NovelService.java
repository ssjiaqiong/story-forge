package com.storyforge.modules.novel.service;

import com.storyforge.common.api.PageResult;
import com.storyforge.modules.novel.dto.NovelDraft;
import com.storyforge.modules.novel.dto.NovelPatch;
import com.storyforge.modules.novel.dto.NovelQuery;
import com.storyforge.modules.novel.dto.NovelVO;

/**
 * 小说服务（按用户隔离）。
 */
public interface NovelService {

    /** 当前用户的小说列表。 */
    PageResult<NovelVO> page(NovelQuery query);

    NovelVO detail(Long id);

    /** 落地一本新书（供灵感模式一键创建、书架创建调用）。 */
    NovelVO createDraft(NovelDraft draft);

    /** 修改书名 / 题材 / 标签 / 简介 / 状态。 */
    NovelVO update(Long id, NovelPatch patch);

    /** 逻辑删除小说（设定与角色由调用方一并清理）。 */
    void delete(Long id);
}
