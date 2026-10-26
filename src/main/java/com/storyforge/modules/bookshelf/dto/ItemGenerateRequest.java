package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 创建物品入参。
 */
@Data
@Schema(description = "AI 创建物品入参")
public class ItemGenerateRequest {

    @Schema(description = "物品需求，如「给主角配一件修复古籍用的工具，再补一个能证明旧街大火的信物」")
    @NotBlank(message = "请先描述你想要的物品")
    @Size(max = 2000, message = "描述最长 2000 个字符")
    private String prompt;

    @Schema(description = "生成数量，默认 3，最多 10")
    @Min(value = 1, message = "至少生成 1 个物品")
    @Max(value = 10, message = "一次最多生成 10 个物品")
    private Integer count;
}
