package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 设定文档视图。
 */
@Data
@Schema(description = "小说设定")
public class SettingDocVO {

    @Schema(description = "分类：WORLDVIEW / BACKGROUND / CORE_CONFLICT / HIGHLIGHT / STYLE")
    private String category;

    @Schema(description = "分类中文名，如「世界观」")
    private String label;

    @Schema(description = "正文；HIGHLIGHT 为多行文本，每行一条")
    private String content;
}
