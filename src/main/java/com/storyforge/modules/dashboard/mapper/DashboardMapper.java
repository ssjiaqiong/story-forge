package com.storyforge.modules.dashboard.mapper;

import com.storyforge.modules.dashboard.dto.DashboardOverviewVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 仪表盘聚合查询。
 *
 * <p>涉及的 {@code t_novel / t_chapter / t_ai_usage} 都已登记到数据隔离清单，
 * 插件会自动追加 {@code user_id} 条件，因此这里不写归属过滤。SQL 见
 * {@code resources/mapper/DashboardMapper.xml}。</p>
 */
@Mapper
public interface DashboardMapper {

    /** 小说按状态分组：数量、字数、章节数 */
    List<DashboardOverviewVO.StatusAgg> countNovelByStatus();

    /** 章节按状态分组：数量、字数 */
    List<DashboardOverviewVO.StatusAgg> countChapterByStatus();

    /** 每日写作量：按章节最后编辑日归集（同章改多次只计最后一次） */
    List<DashboardOverviewVO.TrendPoint> writingByDay(@Param("from") LocalDateTime from);

    /** 每日用量：调用次数与 token */
    List<DashboardOverviewVO.TrendPoint> usageByDay(@Param("from") LocalDateTime from);

    /** 区间内各场景用量 */
    List<DashboardOverviewVO.SceneUsage> usageByScene(@Param("from") LocalDateTime from);

    /** 今日 / 本月 / 累计用量 */
    DashboardOverviewVO.AiStats usageTotals(@Param("todayStart") LocalDateTime todayStart,
                                            @Param("monthStart") LocalDateTime monthStart);
}
