package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑小说与设定的入参。
 *
 * <p>字段为 {@code null} 表示不修改；{@code blueprint} 非空时整体覆盖设定与角色。</p>
 */
@Data
@Schema(description = "编辑小说入参")
public class NovelEditRequest {

    @Schema(description = "书名")
    @Size(max = 200, message = "书名最长 200 个字符")
    private String title;

    @Schema(description = "笔名")
    @Size(max = 64, message = "笔名最长 64 个字符")
    private String penName;

    @Schema(description = "题材")
    @Size(max = 64, message = "题材最长 64 个字符")
    private String genre;

    @Schema(description = "标签")
    @Size(max = 8, message = "标签最多 8 个")
    private List<String> tags;

    @Schema(description = "简介")
    @Size(max = 4000, message = "简介最长 4000 个字符")
    private String intro;

    @Schema(description = "状态：DRAFT / WRITING / FINISHED / ARCHIVED")
    private String status;

    @Schema(description = "预计完结字数；0 表示不设置（「直到完结」自动写书模式需要先设置）")
    private Integer plannedWords;

    @Schema(description = "设定与角色，非空则整体覆盖")
    @Valid
    private NovelBlueprint blueprint;
}
