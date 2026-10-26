package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建自定义技能。
 */
@Data
@Schema(description = "新建技能")
public class SkillCreateRequest {

    @Schema(description = "技能名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "技能名称不能为空")
    @Size(max = 64, message = "技能名称最长 64 个字符")
    private String name;

    @Schema(description = "技能说明")
    @Size(max = 500, message = "技能说明最长 500 个字符")
    private String description;

    @Schema(description = "系统提示词", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "系统提示词不能为空")
    @Size(max = 20000, message = "提示词最长 20000 个字符")
    private String systemPrompt;
}
