package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 物品道具入参。
 */
@Data
@Schema(description = "物品道具入参")
public class ItemRequest {

    @Schema(description = "物品名称")
    @NotBlank(message = "请填写物品名称")
    @Size(max = 100, message = "物品名称最长 100 个字符")
    private String name;

    @Schema(description = "类别，如 法宝 / 信物 / 线索 / 消耗品")
    @Size(max = 32, message = "类别最长 32 个字符")
    private String category;

    @Schema(description = "当前持有者")
    @Size(max = 100, message = "持有者最长 100 个字符")
    private String owner;

    @Schema(description = "物品描述：外貌形态、材质、特征与象征意义")
    @Size(max = 4000, message = "物品描述最长 4000 个字符")
    private String description;

    @Schema(description = "说明：物品来历、经手人、关联的关键故事")
    @Size(max = 4000, message = "说明最长 4000 个字符")
    private String origin;

    @Schema(description = "状态：ACTIVE 活跃 / DESTROYED 摧毁 / ABANDONED 废弃 / LOST 遗失 / CONSUMED 已消耗")
    @Pattern(regexp = "ACTIVE|DESTROYED|ABANDONED|LOST|CONSUMED", message = "物品状态非法")
    private String status;

    @Schema(description = "数量，可空；唯一或不可计数物品留空")
    @Min(value = 0, message = "数量不能为负数")
    @Max(value = 999999, message = "数量过大")
    private Integer quantity;

    @Schema(description = "是否清空数量：为 true 时把数量置空（quantity 传 null 表示不修改）")
    private Boolean clearQuantity;

    @Schema(description = "排序号，越小越靠前")
    private Integer sortNo;
}
