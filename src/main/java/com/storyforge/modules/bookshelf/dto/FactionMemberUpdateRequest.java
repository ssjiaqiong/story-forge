package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改势力成员职位入参。
 */
@Data
@Schema(description = "势力成员职位入参")
public class FactionMemberUpdateRequest {

    @Schema(description = "在该势力中的职位，自由文本")
    @NotBlank(message = "请填写该角色的职位")
    @Size(max = 64, message = "职位最长 64 个字符")
    private String position;
}
