package com.storyforge.modules.bookshelf.dto;

/**
 * 章节关联事件视图（轻量，只带标签需要的字段）。
 */
public record ChapterEventVO(
        Long id,
        String title,
        String status,
        String statusLabel) {
}
