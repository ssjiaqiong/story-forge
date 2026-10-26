package com.storyforge.modules.novel.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.novel.agent.entity.NovelAgentSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小说助手会话 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface NovelAgentSessionMapper extends BaseMapper<NovelAgentSessionEntity> {
}
