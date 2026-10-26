package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 作者对追问的作答。
 *
 * <p>{@code key} 取自 {@link NovelConstraintField}；留空的答案在组装时直接丢弃，
 * 不写「主角设定：（空）」，避免空字段污染后续提示词。</p>
 */
@Data
@Schema(description = "创作约束的作答")
public class ConstraintFormRequest {

    @Schema(description = "逐项作答，按问到的顺序提交即可")
    private List<ConstraintAnswer> answers = new ArrayList<>();

    @Data
    @Schema(description = "单项作答")
    public static class ConstraintAnswer {

        @Schema(description = "固定字段名", example = "protagonist")
        private String key;

        @Schema(description = "作者的答案", example = "被逐出宗门的杂役，背着师门旧案的嫌疑")
        private String value;
    }
}
