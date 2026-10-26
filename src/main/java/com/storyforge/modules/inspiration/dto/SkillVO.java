package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 灵感技能视图。
 */
@Data
@Schema(description = "灵感技能")
public class SkillVO {

    private Long id;

    private String code;

    private String name;

    private String description;

    @Schema(description = "系统提示词；内置技能可修改，修改只对当前用户生效")
    private String systemPrompt;

    @Schema(description = "是否内置技能：可修改、可恢复默认，但不可删除")
    private Boolean builtin;

    private Boolean enabled;

    private Integer sortNo;
}
