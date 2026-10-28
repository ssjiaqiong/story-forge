package com.storyforge.modules.autowrite.dto;

import com.storyforge.modules.autowrite.entity.AutoWriteRunEntity;

import java.time.LocalDateTime;

/**
 * 自动写书执行流水项。
 */
public record AutoWriteRunVO(
        String id,
        String status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        int chaptersWritten,
        int outlinesCreated,
        int masterOutlinesCreated,
        Integer consistencyHard,
        String message) {

    public static AutoWriteRunVO of(AutoWriteRunEntity entity) {
        return new AutoWriteRunVO(
                entity.getId() == null ? null : entity.getId().toString(),
                entity.getStatus(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                intOr(entity.getChaptersWritten()),
                intOr(entity.getOutlinesCreated()),
                intOr(entity.getMasterOutlinesCreated()),
                entity.getConsistencyHard(),
                entity.getMessage());
    }

    private static int intOr(Integer value) {
        return value == null ? 0 : value;
    }
}
