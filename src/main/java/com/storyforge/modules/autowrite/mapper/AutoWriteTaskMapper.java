package com.storyforge.modules.autowrite.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.autowrite.entity.AutoWriteTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自动写书任务 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface AutoWriteTaskMapper extends BaseMapper<AutoWriteTaskEntity> {
}
