package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 一次体检的批次摘要。
 *
 * <p>没有单独的 run 表：批次信息完全可以从 {@code t_consistency_issue} 按 {@code run_id} 聚合出来，
 * 多一张表只会多一处要同步的地方。</p>
 */
public record ConsistencyRunVO(
        String runId,
        @Schema(description = "本次是否跑了 AI 层")
        boolean withAi,
        String scope,
        String scopeLabel,
        LocalDateTime createdAt,
        int total,
        int p0,
        int p1,
        int p2,
        int open) {
}
