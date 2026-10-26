package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 总纲新增 / 修改入参。字段为空表示「不修改」。
 */
@Data
@Schema(description = "总纲入参")
public class MasterOutlineRequest {

    @Schema(description = "总纲标题，如「第一幕 落难与激活」")
    @Size(max = 200, message = "总纲标题最长 200 个字符")
    private String title;

    @Schema(description = "总纲正文：这一段故事要发生什么")
    private String content;

    @Schema(description = "排序号；留空时排到最后")
    private Integer sortNo;
}
