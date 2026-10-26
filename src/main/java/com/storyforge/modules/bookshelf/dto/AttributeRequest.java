package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人物属性新增 / 修改入参。
 */
@Data
@Schema(description = "人物属性入参")
public class AttributeRequest {

    @Schema(description = "属性名，如 体质 / 积分 / 存款 / 等级")
    @NotBlank(message = "请填写属性名")
    @Size(max = 64, message = "属性名最长 64 个字符")
    private String name;

    @Schema(description = "当前值；NUMBER 型填数字，TEXT 型可填「筑基三层」")
    @Size(max = 64, message = "属性值最长 64 个字符")
    private String value;

    @Schema(description = "单位：点 / 元 / 万，可空")
    @Size(max = 16, message = "单位最长 16 个字符")
    private String unit;

    @Schema(description = "类型：NUMBER 数值 / TEXT 文本，默认 NUMBER")
    @Pattern(regexp = "NUMBER|TEXT", message = "属性类型只能是 NUMBER 或 TEXT")
    private String attrType;

    @Schema(description = "备注")
    @Size(max = 255, message = "备注最长 255 个字符")
    private String note;

    @Schema(description = "排序号")
    private Integer sortNo;
}
