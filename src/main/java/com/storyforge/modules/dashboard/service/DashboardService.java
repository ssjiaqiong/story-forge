package com.storyforge.modules.dashboard.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.bookshelf.service.NovelLabels;
import com.storyforge.modules.dashboard.dto.DashboardOverviewVO;
import com.storyforge.modules.dashboard.mapper.DashboardMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import com.storyforge.modules.user.entity.UserEntity;
import com.storyforge.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘聚合服务。
 *
 * <p>所有数字都从真实业务表算：小说 / 章节来自 {@code t_novel}、{@code t_chapter}，
 * token 来自 {@code t_ai_usage}（由 {@code AiUsageService} 在每次模型调用后写入）。
 * 没有可用数据时返回 0 而不是编造默认值，前端据此显示空状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** 趋势图天数（含今天） */
    public static final int TREND_DAYS = 14;
    /** 场景消耗统计窗口 */
    private static final int SCENE_WINDOW_DAYS = 30;
    /** 最近编辑列表条数 */
    private static final int RECENT_LIMIT = 5;

    private static final DateTimeFormatter LABEL_FORMAT = DateTimeFormatter.ofPattern("MM-dd");

    private final DashboardMapper dashboardMapper;
    private final UserMapper userMapper;
    private final NovelMapper novelMapper;

    /** 当前登录用户的仪表盘数据。 */
    public DashboardOverviewVO overview() {
        Long userId = UserContextHolder.requireUserId();
        UserEntity user = userMapper.selectById(userId);

        DashboardOverviewVO vo = new DashboardOverviewVO();
        vo.setProfile(profile(user));
        vo.setNovel(novelStats(dashboardMapper.countNovelByStatus()));
        vo.setChapter(chapterStats(dashboardMapper.countChapterByStatus()));
        vo.setAi(aiStats());
        vo.setTrend(trend());
        vo.setScenes(scenes());
        vo.setRecentNovels(recentNovels());
        return vo;
    }

    /* ------------------------------------------------------------------ */

    private DashboardOverviewVO.Profile profile(UserEntity user) {
        DashboardOverviewVO.Profile profile = new DashboardOverviewVO.Profile();
        if (user == null) {
            return profile;
        }
        profile.setUsername(user.getUsername());
        profile.setNickname(user.getNickname());
        profile.setRoleLabel("SUPER_ADMIN".equals(String.valueOf(user.getRole())) ? "超级管理员" : "普通用户");
        profile.setRegisteredAt(user.getCreatedAt());
        if (user.getCreatedAt() != null) {
            long days = ChronoUnit.DAYS.between(user.getCreatedAt().toLocalDate(), LocalDate.now()) + 1;
            profile.setUsedDays(Math.max(days, 1));
        }
        profile.setLastLoginAt(user.getLastLoginAt());
        profile.setLastLoginIp(user.getLastLoginIp());
        profile.setPrevLoginAt(user.getPrevLoginAt());
        profile.setPrevLoginIp(user.getPrevLoginIp());
        return profile;
    }

    private DashboardOverviewVO.NovelStats novelStats(List<DashboardOverviewVO.StatusAgg> rows) {
        DashboardOverviewVO.NovelStats stats = new DashboardOverviewVO.NovelStats();
        for (DashboardOverviewVO.StatusAgg row : rows) {
            stats.setTotal(stats.getTotal() + row.getCnt());
            stats.setWordCount(stats.getWordCount() + row.getWords());
            stats.setChapterCount(stats.getChapterCount() + row.getChapters());
            switch (String.valueOf(row.getStatus())) {
                case "DRAFT" -> stats.setDraft(stats.getDraft() + row.getCnt());
                case "WRITING" -> stats.setWriting(stats.getWriting() + row.getCnt());
                case "FINISHED" -> stats.setFinished(stats.getFinished() + row.getCnt());
                case "ARCHIVED" -> stats.setArchived(stats.getArchived() + row.getCnt());
                default -> {
                    // 未知状态只计入总数，不影响分类卡片
                }
            }
        }
        if (stats.getChapterCount() > 0) {
            stats.setAvgWordsPerChapter(stats.getWordCount() / stats.getChapterCount());
        }
        return stats;
    }

    private DashboardOverviewVO.ChapterStats chapterStats(List<DashboardOverviewVO.StatusAgg> rows) {
        DashboardOverviewVO.ChapterStats stats = new DashboardOverviewVO.ChapterStats();
        for (DashboardOverviewVO.StatusAgg row : rows) {
            stats.setTotal(stats.getTotal() + row.getCnt());
            switch (String.valueOf(row.getStatus())) {
                case "DRAFT" -> stats.setDraft(stats.getDraft() + row.getCnt());
                case "WRITING" -> stats.setWriting(stats.getWriting() + row.getCnt());
                case "DONE" -> stats.setDone(stats.getDone() + row.getCnt());
                default -> {
                    // 同上：未知状态只进总数
                }
            }
        }
        return stats;
    }

    private DashboardOverviewVO.AiStats aiStats() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();
        LocalDateTime monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay();
        DashboardOverviewVO.AiStats stats = dashboardMapper.usageTotals(todayStart, monthStart);
        return stats == null ? new DashboardOverviewVO.AiStats() : stats;
    }

    /**
     * 近 {@value #TREND_DAYS} 天趋势：先把日期轴铺满，再把写作量与用量填进去。
     *
     * <p>没有数据的日期保持 0，图表才不会跳日。</p>
     */
    private List<DashboardOverviewVO.TrendPoint> trend() {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(TREND_DAYS - 1L);

        Map<LocalDate, DashboardOverviewVO.TrendPoint> points = new LinkedHashMap<>();
        for (int i = 0; i < TREND_DAYS; i++) {
            LocalDate day = from.plusDays(i);
            DashboardOverviewVO.TrendPoint point = new DashboardOverviewVO.TrendPoint();
            point.setDay(day);
            point.setLabel(day.format(LABEL_FORMAT));
            points.put(day, point);
        }

        for (DashboardOverviewVO.TrendPoint row : dashboardMapper.writingByDay(from.atStartOfDay())) {
            DashboardOverviewVO.TrendPoint point = points.get(row.getDay());
            if (point != null) {
                point.setWords(row.getWords());
                point.setChapters(row.getChapters());
            }
        }
        for (DashboardOverviewVO.TrendPoint row : dashboardMapper.usageByDay(from.atStartOfDay())) {
            DashboardOverviewVO.TrendPoint point = points.get(row.getDay());
            if (point != null) {
                point.setTokens(row.getTokens());
                point.setCalls(row.getCalls());
            }
        }
        return new ArrayList<>(points.values());
    }

    /** 场景用量：占比优先按 token 算；网关不返回 usage 时全部为 0，退化成按调用次数算。 */
    private List<DashboardOverviewVO.SceneUsage> scenes() {
        LocalDateTime from = LocalDate.now().minusDays(SCENE_WINDOW_DAYS - 1L).atStartOfDay();
        List<DashboardOverviewVO.SceneUsage> rows = dashboardMapper.usageByScene(from);
        long totalTokens = rows.stream().mapToLong(DashboardOverviewVO.SceneUsage::getTokens).sum();
        long totalCalls = rows.stream().mapToLong(DashboardOverviewVO.SceneUsage::getCalls).sum();
        for (DashboardOverviewVO.SceneUsage row : rows) {
            var scene = AiScene.of(row.getScene());
            row.setLabel(scene == null ? row.getScene() : scene.getLabel());
            row.setGroup(scene == null ? "" : scene.getGroup());
            if (totalTokens > 0) {
                row.setPercent(round(row.getTokens() * 100.0 / totalTokens));
            } else if (totalCalls > 0) {
                row.setPercent(round(row.getCalls() * 100.0 / totalCalls));
            }
        }
        return rows;
    }

    private List<DashboardOverviewVO.RecentNovel> recentNovels() {
        List<NovelEntity> novels = novelMapper.selectList(Wrappers.<NovelEntity>lambdaQuery()
                .orderByDesc(NovelEntity::getUpdatedAt)
                .orderByDesc(NovelEntity::getId)
                .last("LIMIT " + RECENT_LIMIT));
        List<DashboardOverviewVO.RecentNovel> list = new ArrayList<>(novels.size());
        for (NovelEntity novel : novels) {
            DashboardOverviewVO.RecentNovel item = new DashboardOverviewVO.RecentNovel();
            item.setId(novel.getId());
            item.setTitle(novel.getTitle());
            item.setGenre(novel.getGenre());
            item.setStatus(novel.getStatus());
            item.setStatusLabel(NovelLabels.label(NovelLabels.NOVEL_STATUS, novel.getStatus()));
            item.setChapterCount(novel.getChapterCount() == null ? 0 : novel.getChapterCount());
            item.setWordCount(novel.getWordCount() == null ? 0 : novel.getWordCount());
            item.setUpdatedAt(novel.getUpdatedAt());
            list.add(item);
        }
        return list;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
