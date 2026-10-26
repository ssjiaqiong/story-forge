package com.storyforge.modules.inspiration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.inspiration.entity.InspirationSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 灵感会话 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface InspirationSessionMapper extends BaseMapper<InspirationSessionEntity> {
}
