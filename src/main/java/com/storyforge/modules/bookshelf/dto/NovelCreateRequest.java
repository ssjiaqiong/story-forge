package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 保存到书架（创建小说）的入参。
 *
 * <p>书名与简介可直接取自蓝图；{@code blueprint} 为空表示「只建草稿、不落设定」。</p>
 */
@Data
@Schema(description = "创建小说入参")
public class NovelCreateRequest {

    @Schema(description = "书名")
    @NotBlank(message = "书名不能为空")
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

    @Schema(description = "初始化状态：DRAFT 草稿（默认）/ WRITING 连载中")
    private String status;

    @Schema(description = "蓝图与设定；为空则仅创建草稿")
    @Valid
    private NovelBlueprint blueprint;
}
