package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 添加势力成员入参。
 */
@Data
@Schema(description = "势力成员入参")
public class FactionMemberRequest {

    @Schema(description = "角色 ID")
    @NotNull(message = "请选择角色")
    private Long characterId;

    @Schema(description = "在该势力中的职位，自由文本")
    @NotBlank(message = "请填写该角色的职位")
    @Size(max = 64, message = "职位最长 64 个字符")
    private String position;
}
