package com.storyforge.modules.autowrite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.autowrite.entity.AutoWriteRunEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自动写书流水 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface AutoWriteRunMapper extends BaseMapper<AutoWriteRunEntity> {
}
