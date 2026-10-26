package com.storyforge.modules.bookshelf.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 时间线事件视图。
 */
public record TimelineVO(
        Long id,
        String eventTime,
        String title,
        String description,
        String status,
        String statusLabel,
        List<EventParticipantVO> participants,
        Integer sortNo,
        LocalDateTime updatedAt) {
}
