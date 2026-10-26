package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 角色关系入参。关系对称，两端顺序不影响结果。
 */
@Data
@Schema(description = "角色关系入参")
public class RelationRequest {

    @Schema(description = "角色 A 的 ID")
    @NotNull(message = "请选择角色 A")
    private Long fromCharacterId;

    @Schema(description = "角色 B 的 ID")
    @NotNull(message = "请选择角色 B")
    private Long toCharacterId;

    @Schema(description = "关系类型")
    @NotBlank(message = "请选择关系类型")
    @Pattern(regexp = "MASTER_APPRENTICE|FAMILY|SPOUSE|LOVER|FRIEND|ALLY|ENEMY|RIVAL|COLLEAGUE|OTHER",
            message = "关系类型非法")
    private String relationType;

    @Schema(description = "补充说明")
    @Size(max = 255, message = "说明最长 255 个字符")
    private String description;

    @Schema(description = "排序号")
    private Integer sortNo;
}
