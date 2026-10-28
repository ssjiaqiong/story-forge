package com.storyforge.modules.autowrite.dto;

import com.storyforge.modules.autowrite.entity.AutoWriteTaskEntity;

import java.time.LocalDateTime;

/**
 * 自动写书任务配置 + 运行态视图。
 *
 * @param id                      任务 ID（字符串，前端 ID 统一 string）
 * @param novelId                 绑定的书
 * @param enabled                 定时是否开启
 * @param cronExpr                cron 表达式
 * @param chaptersPerRun          每次生成章节数
 * @param endMode                 CHAPTERS / UNTIL_END
 * @param endChapterNo            目标章数（CHAPTERS）
 * @param hardIssueLimit          硬伤熔断阈值
 * @param consistencyAi           体检是否跑 AI 深检
 * @param targetWordsPerChapter   每章目标字数
 * @param status                  运行态 IDLE / RUNNING / PAUSED / STOPPED / COMPLETED
 * @param autoWriting             是否正在自动生成（锁编辑）
 * @param closingTriggered        收尾评估是否已触发
 * @param consecutiveFailures     连续失败周期数
 * @param totalRuns               累计轮次
 * @param generatedChapters       累计生成章节数
 * @param lastRunAt               上次执行时间
 * @param lastResult              上次结果摘要
 * @param stoppedReason           停止原因
 * @param nextRunAt               下次触发时间（由调度器计算，未注册时为 null）
 */
public record AutoWriteTaskVO(
        String id,
        String novelId,
        boolean enabled,
        String cronExpr,
        int chaptersPerRun,
        String endMode,
        int endChapterNo,
        int hardIssueLimit,
        boolean consistencyAi,
        int targetWordsPerChapter,
        String status,
        boolean autoWriting,
        boolean closingTriggered,
        int consecutiveFailures,
        int totalRuns,
        int generatedChapters,
        LocalDateTime lastRunAt,
        String lastResult,
        String stoppedReason,
        LocalDateTime nextRunAt) {

    public static AutoWriteTaskVO of(AutoWriteTaskEntity entity, LocalDateTime nextRunAt) {
        boolean autoWriting = entity.enabledOn()
                && (AutoWriteTaskEntity.STATUS_IDLE.equals(entity.getStatus())
                || AutoWriteTaskEntity.STATUS_RUNNING.equals(entity.getStatus()));
        return new AutoWriteTaskVO(
                entity.getId() == null ? null : entity.getId().toString(),
                entity.getNovelId() == null ? null : entity.getNovelId().toString(),
                entity.enabledOn(),
                entity.getCronExpr(),
                intOr(entity.getChaptersPerRun(), 3),
                entity.getEndMode(),
                intOr(entity.getEndChapterNo(), 0),
                intOr(entity.getHardIssueLimit(), 5),
                entity.consistencyAiOn(),
                intOr(entity.getTargetWordsPerChapter(), 3000),
                entity.getStatus(),
                autoWriting,
                entity.closingTriggeredOn(),
                intOr(entity.getConsecutiveFailures(), 0),
                intOr(entity.getTotalRuns(), 0),
                intOr(entity.getGeneratedChapters(), 0),
                entity.getLastRunAt(),
                entity.getLastResult(),
                entity.getStoppedReason(),
                nextRunAt);
    }

    private static int intOr(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
