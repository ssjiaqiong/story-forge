package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 建书前的追问结果。
 *
 * <p>{@code questions} 可能为空数组：创意本身已经写清楚时，AI 不该硬凑问题。</p>
 */
@Data
@Schema(description = "建书前的追问列表")
public class BlueprintQuestionsVO {

    @Schema(description = "追问列表，0~4 个")
    private List<BlueprintQuestion> questions = new ArrayList<>();

    @Schema(description = "实际使用的模型")
    private String model;
}
