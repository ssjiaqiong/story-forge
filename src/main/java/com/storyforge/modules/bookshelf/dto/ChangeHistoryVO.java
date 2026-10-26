package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 要素变更履历视图。
 */
@Schema(description = "变更履历")
public record ChangeHistoryVO(
        Long id,
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        String entityType,
        String entityTypeLabel,
        Long entityId,
        String entityName,
        String fieldKey,
        String fieldLabel,
        String beforeState,
        String afterState,
        String reason,
        String evidence,
        LocalDateTime createdAt) {
}
