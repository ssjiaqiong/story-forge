package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 角色蓝图（AI 生成结果与保存入参共用）。
 */
@Data
@Schema(description = "角色设定")
public class CharacterDraft {

    @Schema(description = "姓名")
    private String name;

    @Schema(description = "角色定位：PROTAGONIST 主角 / SUPPORTING 配角 / ANTAGONIST 反派 / MINOR 龙套")
    private String rolePos;

    @Schema(description = "身份职位")
    private String identity;

    @Schema(description = "性别，可为空")
    private String gender;

    @Schema(description = "年龄描述，如 十八岁")
    private String ageDesc;

    @Schema(description = "外貌特征")
    private String appearance;

    @Schema(description = "性格")
    private String personality;

    @Schema(description = "核心动机 / 欲望")
    private String motivation;

    @Schema(description = "金手指 / 特殊能力")
    private String goldenFinger;

    @Schema(description = "成长弧线")
    private String arc;
}
