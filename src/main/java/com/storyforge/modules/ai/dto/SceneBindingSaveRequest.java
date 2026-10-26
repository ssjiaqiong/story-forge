package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量保存场景绑定。
 *
 * <p>{@code aiConfigId} 为空表示清除绑定，回落到默认端点。</p>
 */
@Data
@Schema(description = "批量保存场景绑定")
public class SceneBindingSaveRequest {

    @NotEmpty(message = "请至少提交一个场景")
    @Valid
    private List<Item> items;

    @Data
    @Schema(description = "单个场景绑定")
    public static class Item {

        @Schema(description = "场景编码", example = "BODY_GEN")
        @NotBlank(message = "场景编码不能为空")
        private String code;

        @Schema(description = "AI 配置 ID；为空表示清除绑定")
        private Long aiConfigId;

        @Schema(description = "场景专用模型；为空表示沿用端点默认模型")
        @Size(max = 128, message = "模型名称过长")
        private String model;

        @Schema(description = "场景专用温度 0~2；为空表示沿用端点默认值")
        @DecimalMin(value = "0.0", message = "温度不能小于 0")
        @DecimalMax(value = "2.0", message = "温度不能大于 2")
        private Double temperature;

        @Schema(description = "场景专用 max_tokens；为空表示沿用端点默认值")
        @Min(value = 1, message = "max_tokens 至少为 1")
        @Max(value = 200000, message = "max_tokens 过大")
        private Integer maxTokens;
    }
}
