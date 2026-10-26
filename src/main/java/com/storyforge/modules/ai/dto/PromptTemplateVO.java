package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 提示词模板列表项 / 详情。
 */
public final class PromptTemplateVO {

    private PromptTemplateVO() {
    }

    @Schema(description = "模板列表项")
    public record Item(
            String scene,
            String displayName,
            String group,
            boolean editable,
            boolean overridden,
            int variableCount,
            LocalDateTime updatedAt) {
    }

    @Schema(description = "模板详情")
    public record Detail(
            String scene,
            String displayName,
            String group,
            boolean editable,
            boolean overridden,
            String content,
            String defaultContent,
            String outputContract,
            List<Variable> variables,
            String note,
            LocalDateTime updatedAt) {
    }

    @Schema(description = "可用变量")
    public record Variable(String name, String label, String sample) {
    }

    @Schema(description = "预览结果：渲染后的用户段 + 锁定的输出格式段")
    public record Preview(
            String scene,
            String content,
            String outputContract,
            String full) {
    }
}
