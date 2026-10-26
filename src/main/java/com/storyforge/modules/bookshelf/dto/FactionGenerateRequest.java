package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 生成势力入参。
 */
@Data
@Schema(description = "AI 生成势力入参")
public class FactionGenerateRequest {

    @Schema(description = "势力需求，如「围绕宗门与朝廷设计两个对立的势力」")
    @NotBlank(message = "请先描述你想要的势力")
    @Size(max = 2000, message = "描述最长 2000 个字符")
    private String prompt;

    @Schema(description = "生成数量，默认 2，最多 8")
    @Min(value = 1, message = "至少生成 1 个势力")
    @Max(value = 8, message = "一次最多生成 8 个势力")
    private Integer count;
}
