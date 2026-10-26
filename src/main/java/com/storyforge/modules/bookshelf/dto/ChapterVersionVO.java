package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 历史版本列表项：**不带正文**，避免一次拉回整章的几十份全文。
 */
@Schema(description = "章节历史版本（列表项）")
public record ChapterVersionVO(
        Long id,
        @Schema(description = "章内序号，越大越新") Integer versionNo,
        @Schema(description = "MANUAL/AI_BODY/AI_REVISE/BATCH/RESTORE/IMPORT/AUTO") String source,
        @Schema(description = "来源中文名") String sourceLabel,
        @Schema(description = "当时的章节标题") String title,
        Integer wordCount,
        String note,
        LocalDateTime createdAt) {
}
