package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 人物背包条目新增 / 修改入参。
 *
 * <p>{@code entryType=ITEM} 时 {@code itemId} 必填，名称以物品档案为准；
 * {@code CURRENCY} / {@code MISC} 时名称必填，不关联物品档案。</p>
 */
@Data
@Schema(description = "人物背包条目入参")
public class InventoryRequest {

    @Schema(description = "类型：ITEM 关联物品 / CURRENCY 货币 / MISC 常规物资")
    @Pattern(regexp = "ITEM|CURRENCY|MISC", message = "背包类型只能是 ITEM / CURRENCY / MISC")
    private String entryType;

    @Schema(description = "物品档案 ID，entryType=ITEM 时必填")
    private Long itemId;

    @Schema(description = "名称：ITEM 可空（取物品名），CURRENCY / MISC 必填")
    @Size(max = 100, message = "名称最长 100 个字符")
    private String name;

    @Schema(description = "数量或金额，可空")
    @DecimalMin(value = "0", message = "数量不能为负数")
    @DecimalMax(value = "999999999999999", message = "数量过大")
    private BigDecimal quantity;

    @Schema(description = "单位：个 / 枚 / 元 / 两")
    @Size(max = 16, message = "单位最长 16 个字符")
    private String unit;

    @Schema(description = "状态：HELD 持有 / LOST 遗失 / CONSUMED 已消耗")
    @Pattern(regexp = "HELD|LOST|CONSUMED", message = "背包状态非法")
    private String status;

    @Schema(description = "备注")
    @Size(max = 1000, message = "备注最长 1000 个字符")
    private String note;

    @Schema(description = "排序号")
    private Integer sortNo;

    @Schema(description = "清空数量（update 时用：数值型字段 updateById 会跳过 null）")
    private Boolean clearQuantity;
}
