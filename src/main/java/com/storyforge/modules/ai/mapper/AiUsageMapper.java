package com.storyforge.modules.ai.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.ai.entity.AiUsageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 用量流水 Mapper。
 *
 * <p>只做写入；仪表盘的聚合统计放在
 * {@code com.storyforge.modules.dashboard.mapper.DashboardMapper}，避免把报表 SQL 混进埋点。</p>
 */
@Mapper
public interface AiUsageMapper extends BaseMapper<AiUsageEntity> {
}
