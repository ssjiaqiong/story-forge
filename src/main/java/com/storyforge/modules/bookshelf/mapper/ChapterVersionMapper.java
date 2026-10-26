package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.ChapterVersionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 章节版本 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface ChapterVersionMapper extends BaseMapper<ChapterVersionEntity> {
}
