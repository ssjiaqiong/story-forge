package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 生成总纲入参。
 */
@Data
@Schema(description = "AI 生成总纲入参")
public class MasterOutlineGenerateRequest {

    @Schema(description = "本次总纲的要求，如「重点写主角从地洞走向跨区」；留空则按现有设定推进")
    @Size(max = 2000, message = "要求最长 2000 个字符")
    private String prompt;

    @Schema(description = "生成条数；留空时按资料充足程度自动决定（充足则覆盖全书，不足只生成一部分）")
    @Min(value = 1, message = "至少生成 1 条")
    @Max(value = 12, message = "一次最多生成 12 条")
    private Integer count;
}
