package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 章节分析历史摘要（列表用，不含完整报告）。
 */
@Schema(description = "章节分析摘要")
public record ChapterAnalysisSummaryVO(
        Long id,
        Long chapterId,
        Integer chapterNo,
        String chapterTitle,
        Integer wordCount,
        Integer targetWords,
        Boolean wordPass,
        Integer outlineScore,
        Integer aiScore,
        String model,
        LocalDateTime createdAt) {
}
