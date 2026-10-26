package com.storyforge.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 通用分页入参。
 */
@Data
public class PageQuery implements Serializable {

    private static final long MAX_SIZE = 200L;

    @Schema(description = "页码，从 1 开始", example = "1")
    private Long page = 1L;

    @Schema(description = "每页条数，1~200", example = "20")
    private Long size = 20L;

    public long normalizedPage() {
        return (page == null || page < 1) ? 1L : page;
    }

    public long normalizedSize() {
        if (size == null || size < 1) {
            return 20L;
        }
        return Math.min(size, MAX_SIZE);
    }
}
