package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 切换当前技能。
 */
@Data
@Schema(description = "切换技能")
public class SwitchSkillRequest {

    @Schema(description = "技能编码", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "技能编码不能为空")
    private String skillCode;
}
