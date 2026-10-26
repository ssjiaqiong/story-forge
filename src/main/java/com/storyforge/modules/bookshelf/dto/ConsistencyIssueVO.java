package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 一致性问题视图。
 *
 * <p>{@code typeLabel}、{@code severityLabel}、{@code sourceLabel}、{@code statusLabel} 由后端给出中文，
 * 前端不维护映射表——同一个标签在体检页与「按分析修改」的预填里必须长一样。</p>
 */
public record ConsistencyIssueVO(
        Long id,
        String runId,
        String source,
        @Schema(description = "RULE 规则层 / AI 模型层")
        String sourceLabel,
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        String issueType,
        String typeLabel,
        String severity,
        String severityLabel,
        String title,
        String detail,
        String evidence,
        String suggestion,
        String status,
        String statusLabel,
        LocalDateTime createdAt) {
}
