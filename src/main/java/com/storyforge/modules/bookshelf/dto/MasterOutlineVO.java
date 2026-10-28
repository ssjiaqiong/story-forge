package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * 总纲视图。
 *
 * @param chapterCount    已关联到这条总纲的章节大纲数量
 * @param status          PENDING 未开始 / RUNNING 进行中 / DONE 已完成
 * @param plannedOutlines 预计大纲数量，只作参考（0 表示未规划）
 */
@Schema(description = "总纲视图")
public record MasterOutlineVO(
        Long id,
        Integer sortNo,
        String title,
        String content,
        @Schema(description = "关联的章节大纲数量") int chapterCount,
        @Schema(description = "推进状态：PENDING / RUNNING / DONE") String status,
        @Schema(description = "预计大纲数量，仅供参考") Integer plannedOutlines,
        LocalDateTime updatedAt) {
}
