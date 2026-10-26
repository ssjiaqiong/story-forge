package com.storyforge.modules.dashboard.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 仪表盘总览。
 *
 * <p>一次请求返回首屏需要的全部数字，避免前端并发打十几个接口；
 * 所有查询都由数据隔离插件按当前登录用户过滤，不含跨用户聚合。</p>
 */
@Data
public class DashboardOverviewVO implements Serializable {

    /** 账号与登录信息 */
    private Profile profile;
    /** 小说维度统计 */
    private NovelStats novel;
    /** 章节维度统计 */
    private ChapterStats chapter;
    /** AI 用量统计 */
    private AiStats ai;
    /** 近 14 天趋势（含今天），按日期升序 */
    private List<TrendPoint> trend;
    /** 近 30 天各场景用量，按 token 降序 */
    private List<SceneUsage> scenes;
    /** 最近编辑的小说，最多 5 本 */
    private List<RecentNovel> recentNovels;

    @Data
    public static class Profile implements Serializable {
        private String username;
        private String nickname;
        @Schema(description = "角色中文名：超级管理员 / 普通用户")
        private String roleLabel;
        private LocalDateTime registeredAt;
        @Schema(description = "账号已使用天数（含注册当天）")
        private long usedDays;
        @Schema(description = "本次登录时间")
        private LocalDateTime lastLoginAt;
        private String lastLoginIp;
        @Schema(description = "上一次登录时间，首次登录为空")
        private LocalDateTime prevLoginAt;
        private String prevLoginIp;
    }

    @Data
    public static class NovelStats implements Serializable {
        private int total;
        private int draft;
        @Schema(description = "连载中")
        private int writing;
        @Schema(description = "已完结")
        private int finished;
        @Schema(description = "已归档")
        private int archived;
        @Schema(description = "全部小说字数合计")
        private int wordCount;
        @Schema(description = "全部小说章节数合计")
        private int chapterCount;
        @Schema(description = "平均每章字数")
        private int avgWordsPerChapter;
    }

    @Data
    public static class ChapterStats implements Serializable {
        private int total;
        @Schema(description = "已完成正文")
        private int done;
        @Schema(description = "写作中")
        private int writing;
        @Schema(description = "草稿")
        private int draft;
    }

    @Data
    public static class AiStats implements Serializable {
        private int todayTokens;
        private int todayCalls;
        private int todayPromptTokens;
        private int todayCompletionTokens;
        @Schema(description = "今日失败的调用次数")
        private int todayFailed;
        private int monthTokens;
        private int monthCalls;
        private int totalTokens;
        private int totalCalls;
        @Schema(description = "最近一次调用时间")
        private LocalDateTime lastCalledAt;
    }

    @Data
    public static class TrendPoint implements Serializable {
        private LocalDate day;
        @Schema(description = "前端展示用，格式 MM-dd")
        private String label;
        private int words;
        private int chapters;
        private int tokens;
        private int calls;
    }

    @Data
    public static class SceneUsage implements Serializable {
        private String scene;
        @Schema(description = "AiScene 中文名")
        private String label;
        @Schema(description = "界面分组")
        private String group;
        private int calls;
        private int tokens;
        @Schema(description = "占比，0~100")
        private double percent;
    }

    @Data
    public static class RecentNovel implements Serializable {
        private Long id;
        private String title;
        private String genre;
        private String status;
        private String statusLabel;
        private int chapterCount;
        private int wordCount;
        private LocalDateTime updatedAt;
    }

    /** 分组统计行（小说 / 章节共用） */
    @Data
    public static class StatusAgg implements Serializable {
        private String status;
        private int cnt;
        private int words;
        private int chapters;
    }
}
