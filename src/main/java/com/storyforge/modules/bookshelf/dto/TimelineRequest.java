package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 时间线事件入参。
 */
@Data
@Schema(description = "时间线事件入参")
public class TimelineRequest {

    @Schema(description = "故事内时间描述，如「第三年春」")
    @Size(max = 64, message = "时间描述最长 64 个字符")
    private String eventTime;

    @Schema(description = "事件标题")
    @NotBlank(message = "请填写事件标题")
    @Size(max = 200, message = "事件标题最长 200 个字符")
    private String title;

    @Schema(description = "事件描述")
    private String description;

    @Schema(description = "状态：UNUSED 未使用 / ONGOING 进行中 / DONE 已完成 / ABANDONED 废弃")
    @Pattern(regexp = "UNUSED|ONGOING|DONE|ABANDONED", message = "事件状态非法")
    private String status;

    @Schema(description = "参与角色 ID 列表；传 null 表示不改，传空数组表示清空")
    private List<Long> participantIds;

    @Schema(description = "排序号，越小越靠前")
    private Integer sortNo;
}
