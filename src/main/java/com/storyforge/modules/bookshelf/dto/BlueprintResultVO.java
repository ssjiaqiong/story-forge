package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 一次蓝图生成的返回：内容 + 调用元信息。
 */
@Data
@Schema(description = "小说蓝图生成结果")
public class BlueprintResultVO {

    @Schema(description = "蓝图内容")
    private NovelBlueprint blueprint;

    @Schema(description = "实际使用的模型")
    private String model;

    @Schema(description = "耗时（毫秒）")
    private Long latencyMs;
}
