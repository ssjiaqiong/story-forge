package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 角色视图。
 */
@Data
@Schema(description = "小说角色")
public class CharacterVO {

    private Long id;

    private String name;

    @Schema(description = "角色定位：PROTAGONIST / SUPPORTING / ANTAGONIST / MINOR")
    private String rolePos;

    @Schema(description = "定位中文名")
    private String roleLabel;

    private String gender;

    private String ageDesc;

    @Schema(description = "身份职位")
    private String identity;

    private String appearance;

    private String personality;

    private String motivation;

    @Schema(description = "金手指 / 特殊能力")
    private String goldenFinger;

    @Schema(description = "成长弧线")
    private String arc;
}
