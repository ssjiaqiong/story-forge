package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 蓝图生成任务视图（前端按此轮询进度）。
 *
 * @param taskId       任务 ID
 * @param status       RUNNING / SUCCEEDED / FAILED
 * @param percent      进度百分比 0~100
 * @param stepNo       当前第几段（从 1 开始，整理阶段等于总段数）
 * @param stepTotal    总段数
 * @param stepLabel    当前阶段文案，如「正在生成世界观」
 * @param message      补充说明（重试提示等）
 * @param result       成功时的蓝图结果
 * @param errorMessage 失败原因
 */
@Schema(description = "蓝图生成任务")
public record BlueprintTaskVO(
        String taskId,
        String status,
        int percent,
        int stepNo,
        int stepTotal,
        String stepLabel,
        String message,
        BlueprintResultVO result,
        String errorMessage) {
}
