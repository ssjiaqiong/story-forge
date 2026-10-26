package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 章节分析入参。
 */
@Data
@Schema(description = "章节分析入参")
public class ChapterAnalysisRequest {

    @Schema(description = "目标字数，留空按全书平均章字数（无正文时按 2500）")
    private Integer targetWords;
}
