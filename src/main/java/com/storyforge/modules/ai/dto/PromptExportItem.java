package com.storyforge.modules.ai.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 一个场景的提示词导出形态（导入的入参也是它）。
 *
 * <p>{@code displayName} / {@code group} 只用于展示与人工核对；导入时以 {@code scene} 为准，
 * 不同版本可能给同一场景改了中文名。</p>
 */
@Data
@Schema(description = "提示词模板导出项")
public class PromptExportItem {

    /** 场景编码（AiScene.name()），导入时的唯一依据 */
    private String scene;

    /** 场景中文名，仅展示 */
    private String displayName;

    /** 分组，仅展示 */
    private String group;

    /** 可编辑的指令段正文 */
    private String content;
}
