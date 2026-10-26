package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 一键建书任务视图（前端按此轮询进度）。
 *
 * @param taskId       任务 ID
 * @param status       RUNNING / SUCCEEDED / FAILED
 * @param percent      进度百分比 0~100
 * @param stepNo       当前第几步（从 1 开始）
 * @param stepTotal    总步数
 * @param stepLabel    当前阶段文案，如「正在生成关键人物」
 * @param message      补充说明（如某可选步骤被跳过）
 * @param novelId      成功创建的小说 ID
 * @param novelTitle   书名
 * @param errorMessage 失败原因
 */
@Schema(description = "一键建书任务")
public record NovelCreationTaskVO(
        String taskId,
        String status,
        int percent,
        int stepNo,
        int stepTotal,
        String stepLabel,
        String message,
        Long novelId,
        String novelTitle,
        String errorMessage) {
}
