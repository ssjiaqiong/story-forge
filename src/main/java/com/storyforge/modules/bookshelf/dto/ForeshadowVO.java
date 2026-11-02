package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 伏笔台账的一条记录。
 *
 * <p>{@code overdue} 是算出来的，不是存出来的：超期判定随「当前写到第几章」变化，
 * 存下来就会过期。前端拿它做红色标记，规则层的 R9 用的是同一套算法。</p>
 */
@Schema(description = "伏笔")
public record ForeshadowVO(
        Long id,
        String title,
        String content,
        Integer plantedChapterNo,
        Integer plannedPayoffChapter,
        @Schema(description = "OPEN 未回收 / PAID 已回收 / DROPPED 放弃")
        String status,
        String statusLabel,
        Integer payoffChapterNo,
        @Schema(description = "是否已超期（OPEN 且超过计划回收章或默认周期）")
        boolean overdue,
        @Schema(description = "超期多少章；未超期为 0")
        int overdueChapters,
        String note) {
}
