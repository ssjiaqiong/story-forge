package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 总纲 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface MasterOutlineMapper extends BaseMapper<MasterOutlineEntity> {
}
