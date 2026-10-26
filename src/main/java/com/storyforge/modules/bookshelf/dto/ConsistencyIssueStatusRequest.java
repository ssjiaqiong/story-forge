package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 修改一条问题的处理状态。
 *
 * <p>只有三个值，且只由这一个接口写入：OPEN（重新关注）/ IGNORED（我看过了，别再报）
 * / FIXED（已按建议改完）。不做批量接口——一条一条点，才是作者真的看过。</p>
 */
@Data
@Schema(description = "一致性问题状态修改")
public class ConsistencyIssueStatusRequest {

    @Schema(description = "OPEN / IGNORED / FIXED", example = "IGNORED")
    @NotBlank(message = "状态不能为空")
    private String status;
}
