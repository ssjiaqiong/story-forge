package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.VolumeEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 卷 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface VolumeMapper extends BaseMapper<VolumeEntity> {
}
