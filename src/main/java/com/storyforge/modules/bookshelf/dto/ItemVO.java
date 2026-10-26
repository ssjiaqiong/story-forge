package com.storyforge.modules.bookshelf.dto;

import java.time.LocalDateTime;

/**
 * 物品道具视图。
 */
public record ItemVO(
        Long id,
        String name,
        String category,
        String owner,
        String description,
        String origin,
        String status,
        String statusLabel,
        Integer quantity,
        Integer sortNo,
        LocalDateTime updatedAt) {
}
