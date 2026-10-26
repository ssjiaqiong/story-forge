package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 角色新增 / 修改入参（工作台「人物」菜单使用）。
 */
@Data
@Schema(description = "角色入参")
public class CharacterSaveRequest {

    @Schema(description = "姓名")
    @NotBlank(message = "请填写角色姓名")
    @Size(max = 64, message = "姓名最长 64 个字符")
    private String name;

    @Schema(description = "定位：PROTAGONIST / SUPPORTING / ANTAGONIST / MINOR")
    private String rolePos;

    @Schema(description = "身份职位")
    @Size(max = 255, message = "身份最长 255 个字符")
    private String identity;

    @Schema(description = "性别")
    @Size(max = 16, message = "性别最长 16 个字符")
    private String gender;

    @Schema(description = "年龄描述")
    @Size(max = 32, message = "年龄描述最长 32 个字符")
    private String ageDesc;

    @Schema(description = "外貌特征")
    private String appearance;

    @Schema(description = "性格")
    private String personality;

    @Schema(description = "核心动机")
    private String motivation;

    @Schema(description = "金手指 / 独有优势")
    private String goldenFinger;

    @Schema(description = "成长弧线")
    private String arc;
}
