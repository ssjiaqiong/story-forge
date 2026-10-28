package com.storyforge.modules.novel.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.novel.agent.entity.NovelAgentPlanEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 智能助手任务计划 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface NovelAgentPlanMapper extends BaseMapper<NovelAgentPlanEntity> {
}
