package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一致性检查任务的进度快照。
 *
 * <p>与连写任务同规格：内存任务 + 前端轮询。规则阶段秒级完成，AI 阶段按块推进，
 * 因此 {@code stepLabel} 会依次变成「正在跑规则检查」→「正在读第 N~M 章」。</p>
 */
public record ConsistencyTaskVO(
        String taskId,
        String novelId,
        @Schema(description = "RUNNING / SUCCEEDED / PARTIAL / FAILED / CANCELLED")
        String status,
        int percent,
        String stepLabel,
        String runId,
        String scope,
        String scopeLabel,
        int blockCount,
        int blockDone,
        int ruleCount,
        int aiCount,
        int total,
        int p0,
        int p1,
        int p2,
        List<ConsistencyIssueVO> issues,
        String message,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt) {
}
