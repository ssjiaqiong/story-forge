package com.storyforge.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.ai.entity.AiConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 接口配置 Mapper。
 *
 * <p>数据隔离由 MyBatis-Plus 数据权限插件按 {@code user_id} 自动改写，无需手写条件。</p>
 */
@Mapper
public interface AiConfigMapper extends BaseMapper<AiConfigEntity> {
}
