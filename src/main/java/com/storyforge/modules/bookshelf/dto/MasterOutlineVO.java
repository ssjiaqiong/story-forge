package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 总纲视图。
 *
 * @param chapterCount 已关联到这条总纲的章节大纲数量
 */
@Schema(description = "总纲视图")
public record MasterOutlineVO(
        Long id,
        Integer sortNo,
        String title,
        String content,
        @Schema(description = "关联的章节大纲数量") int chapterCount,
        LocalDateTime updatedAt) {
}
