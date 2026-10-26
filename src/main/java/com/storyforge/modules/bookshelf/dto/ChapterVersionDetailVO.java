package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 单个历史版本的全文，用于「查看」与「对比当前」。
 */
@Schema(description = "章节历史版本（含正文）")
public record ChapterVersionDetailVO(
        Long id,
        Integer versionNo,
        String source,
        String sourceLabel,
        String title,
        String content,
        Integer wordCount,
        String note,
        LocalDateTime createdAt) {
}
