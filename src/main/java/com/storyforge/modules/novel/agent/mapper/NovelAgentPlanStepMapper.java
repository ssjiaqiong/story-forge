package com.storyforge.modules.novel.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.novel.agent.entity.NovelAgentPlanStepEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 计划步骤 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface NovelAgentPlanStepMapper extends BaseMapper<NovelAgentPlanStepEntity> {
}
