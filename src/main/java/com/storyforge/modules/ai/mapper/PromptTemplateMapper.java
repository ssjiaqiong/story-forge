package com.storyforge.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.ai.entity.PromptTemplateEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 提示词模板 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface PromptTemplateMapper extends BaseMapper<PromptTemplateEntity> {
}
