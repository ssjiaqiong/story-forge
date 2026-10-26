package com.storyforge.modules.bookshelf.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 势力视图。
 */
public record FactionVO(
        Long id,
        String name,
        String category,
        String remark,
        String scope,
        String status,
        String statusLabel,
        int memberCount,
        List<FactionMemberVO> members,
        LocalDateTime updatedAt) {
}
