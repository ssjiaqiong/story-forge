package com.storyforge.modules.novel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 超管批量物理删除（彻底清除）已逻辑删除的小说。
 */
@Data
@Schema(description = "超管批量物理删除请求")
public class AdminPurgeRequest {

    @Schema(description = "小说 ID 列表（单次最多 50 本）")
    @NotEmpty(message = "请选择要清除的小说")
    @Size(max = 50, message = "单次最多清除 50 本小说")
    private List<Long> ids;
}
