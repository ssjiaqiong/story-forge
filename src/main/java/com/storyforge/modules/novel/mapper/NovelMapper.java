package com.storyforge.modules.novel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小说 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface NovelMapper extends BaseMapper<NovelEntity> {
}
