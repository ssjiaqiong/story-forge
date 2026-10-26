package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 建书前 AI 追问的一个问题。
 *
 * <p>{@code key} 是固定枚举值（见 {@link NovelConstraintField}），而不是 AI 自由命名：
 * 只有固定的 key 才能稳定落库、稳定组装成「创作约束」，模型换个说法也不会把数据写乱。</p>
 */
@Data
@Schema(description = "建书前的追问")
public class BlueprintQuestion {

    @Schema(description = "固定字段名，如 protagonist / goal / antagonist", example = "protagonist")
    private String key;

    @Schema(description = "字段的中文名，用于组装创作约束与前端展示", example = "主角设定")
    private String label;

    @Schema(description = "要问的问题", example = "主角出场时是什么身份？已经被逼到什么份上了？")
    private String question;

    @Schema(description = "示例答案，告诉作者该写到什么颗粒度")
    private String hint;
}
