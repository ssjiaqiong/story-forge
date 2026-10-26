package com.storyforge.modules.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设置页「试搜」入参。
 */
@Data
public class SearchPreviewRequest {

    @NotBlank(message = "请输入检索词")
    @Size(max = 200, message = "检索词不能超过 200 字")
    private String query;

    /** 为空表示使用默认搜索源 */
    private Long configId;

    @Min(value = 1, message = "返回条数至少 1 条")
    @Max(value = 10, message = "返回条数最多 10 条")
    private Integer topK;
}
