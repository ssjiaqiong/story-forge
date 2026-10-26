package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 势力新增 / 修改入参。
 */
@Data
@Schema(description = "势力入参")
public class FactionRequest {

    @Schema(description = "势力名称")
    @NotBlank(message = "请填写势力名称")
    @Size(max = 100, message = "势力名称最长 100 个字符")
    private String name;

    @Schema(description = "势力类型：公司 / 帮派 / 国家 / 组织 / 其他")
    @Size(max = 32, message = "势力类型最长 32 个字符")
    private String category;

    @Schema(description = "备注")
    @Size(max = 1000, message = "备注最长 1000 个字符")
    private String remark;

    @Schema(description = "营业范围或管辖范围")
    @Size(max = 500, message = "范围最长 500 个字符")
    private String scope;

    @Schema(description = "状态：ACTIVE 活跃 / DORMANT 蛰伏 / DESTROYED 覆灭 / DISBANDED 解散")
    @Pattern(regexp = "ACTIVE|DORMANT|DESTROYED|DISBANDED", message = "势力状态非法")
    private String status;

    @Schema(description = "排序号")
    private Integer sortNo;
}
