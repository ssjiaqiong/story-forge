package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 场景绑定视图（一行 = 一个 AI 场景）。
 */
@Data
@Schema(description = "场景绑定视图")
public class SceneBindingVO {

    @Schema(description = "场景编码")
    private String code;

    @Schema(description = "场景中文名")
    private String label;

    @Schema(description = "界面分组")
    private String group;

    @Schema(description = "该场景需要的接口类型：TEXT 文本 / IMAGE 图像，界面据此过滤可选项")
    private String kind;

    @Schema(description = "绑定的 AI 配置 ID；为空表示继承默认端点")
    private Long aiConfigId;

    @Schema(description = "绑定的 AI 配置名称")
    private String aiConfigName;

    @Schema(description = "场景专用模型；为空表示沿用端点默认模型")
    private String model;

    @Schema(description = "场景专用温度；为空表示沿用端点默认值")
    private Double temperature;

    @Schema(description = "场景专用 max_tokens；为空表示沿用端点默认值")
    private Integer maxTokens;
}
