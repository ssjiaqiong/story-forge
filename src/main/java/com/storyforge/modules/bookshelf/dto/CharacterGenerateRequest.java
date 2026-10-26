package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * AI 创建角色的入参。
 */
@Data
@Schema(description = "AI 创建角色入参")
public class CharacterGenerateRequest {

    @Schema(description = "角色需求描述，如「加两个旧书商会的打手，一个忠心一个反水」")
    @NotBlank(message = "请先描述你想要的角色")
    @Size(max = 2000, message = "描述最长 2000 个字符")
    private String prompt;

    @Schema(description = "希望生成的角色数量，默认 2，最多 6")
    @Min(value = 1, message = "至少生成 1 个角色")
    @Max(value = 6, message = "一次最多生成 6 个角色")
    private Integer count;
}
