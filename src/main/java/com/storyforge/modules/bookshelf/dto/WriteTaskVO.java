package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 批量连写任务视图（前端按此轮询进度）。
 *
 * @param taskId            任务 ID
 * @param novelId           所属小说
 * @param status            RUNNING / SUCCEEDED / PARTIAL（部分失败）/ FAILED / CANCELLED
 * @param total             计划要写的章数（count）
 * @param done              已写完的章数
 * @param percent           进度 0~100，按「已写完章数 / 计划章数」算——每章耗时差异很大，不能用时间估算
 * @param currentChapterNo  正在写的章号
 * @param currentTitle      正在写的章节标题
 * @param stepLabel         当前阶段文案
 * @param writtenWords      本次连写总共写下的汉字数
 * @param succeeded         成功章数（= done）
 * @param failed            失败章数
 * @param skipped           跳过的已完成章数
 * @param chapters          逐章进展清单
 * @param message           补充说明（如「后面没有更多章节了」）
 * @param errorMessage      整批失败时的原因
 * @param startedAt         任务开始时间（毫秒时间戳）
 * @param finishedAt        任务结束时间，进行中为 null
 */
@Schema(description = "批量连写任务")
public record WriteTaskVO(
        String taskId,
        String novelId,
        String status,
        int total,
        int done,
        int percent,
        Integer currentChapterNo,
        String currentTitle,
        String stepLabel,
        Long writtenWords,
        int succeeded,
        int failed,
        int skipped,
        List<WriteTaskChapterVO> chapters,
        String message,
        String errorMessage,
        Long startedAt,
        Long finishedAt) {
}
