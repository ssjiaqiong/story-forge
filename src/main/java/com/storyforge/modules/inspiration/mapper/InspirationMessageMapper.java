package com.storyforge.modules.inspiration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.inspiration.entity.InspirationMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 灵感消息 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface InspirationMessageMapper extends BaseMapper<InspirationMessageEntity> {
}
