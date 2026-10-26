package com.storyforge.modules.novel.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.novel.entity.NovelCoverEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小说封面 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface NovelCoverMapper extends BaseMapper<NovelCoverEntity> {
}
