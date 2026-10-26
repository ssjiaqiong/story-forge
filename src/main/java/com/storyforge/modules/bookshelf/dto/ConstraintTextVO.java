package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 组装好的创作约束文本。
 *
 * <p>前端把这段文本原样放进 {@code BlueprintRequest.constraintText} 再开始生成蓝图，
 * 建书时随蓝图落库到 {@code t_novel.constraint_text}。</p>
 */
@Data
@Schema(description = "创作约束文本")
public class ConstraintTextVO {

    @Schema(description = "逐行文本：每行「中文名：答案」", example = "主角设定：被逐出宗门的杂役\n主角目标：找回失传的残卷")
    private String constraintText;
}
