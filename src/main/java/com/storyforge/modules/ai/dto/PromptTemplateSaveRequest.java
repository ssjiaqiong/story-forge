package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 保存提示词模板入参。
 */
@Data
@Schema(description = "提示词模板保存入参")
public class PromptTemplateSaveRequest {

    @Schema(description = "模板正文，支持 {{变量}}；输出格式段不需要也不允许写在这里")
    @NotBlank(message = "模板内容不能为空")
    @Size(max = 8000, message = "模板最长 8000 个字符")
    private String content;
}
