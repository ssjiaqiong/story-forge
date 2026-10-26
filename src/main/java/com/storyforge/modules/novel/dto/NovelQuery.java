package com.storyforge.modules.novel.dto;

import com.storyforge.common.api.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 小说列表查询条件。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "小说列表查询")
public class NovelQuery extends PageQuery {

    @Schema(description = "关键字，匹配书名或简介")
    private String keyword;

    @Schema(description = "状态：DRAFT / WRITING / FINISHED / ARCHIVED")
    private String status;
}
