package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小说角色 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface CharacterMapper extends BaseMapper<CharacterEntity> {
}
