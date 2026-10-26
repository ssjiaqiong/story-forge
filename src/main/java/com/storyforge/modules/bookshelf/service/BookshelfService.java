package com.storyforge.modules.bookshelf.service;

import com.storyforge.modules.bookshelf.dto.BlueprintRequest;
import com.storyforge.modules.bookshelf.dto.BlueprintResultVO;
import com.storyforge.modules.bookshelf.dto.NovelCreateRequest;
import com.storyforge.modules.bookshelf.dto.NovelDetailVO;
import com.storyforge.modules.bookshelf.dto.NovelEditRequest;
import com.storyforge.modules.novel.dto.NovelVO;

/**
 * 书架服务：AI 生成设定、创建小说、查看与编辑设定。
 */
public interface BookshelfService {

    /** 按用户输入调用 AI，生成世界观 / 背景 / 简介 / 主角等设定。 */
    BlueprintResultVO generateBlueprint(BlueprintRequest request);

    /** 保存到书架：创建小说，并按需落设定与角色。 */
    NovelVO create(NovelCreateRequest request);

    /** 小说详情（基本信息 + 设定 + 角色）。 */
    NovelDetailVO detail(Long id);

    /** 编辑基本信息；{@code blueprint} 非空时整体覆盖设定与角色。 */
    NovelVO update(Long id, NovelEditRequest request);

    /** 删除小说及其设定与角色。 */
    void delete(Long id);
}
