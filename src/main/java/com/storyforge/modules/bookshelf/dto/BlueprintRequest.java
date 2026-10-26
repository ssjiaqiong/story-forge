package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 生成小说蓝图的入参：用户填的必要信息。
 *
 * <p>除 {@code idea} 外全部可选，AI 会按已有信息做合理补全。</p>
 */
@Data
@Schema(description = "小说蓝图生成入参")
public class BlueprintRequest {

    @Schema(description = "书名，留空由 AI 拟定")
    @Size(max = 100, message = "书名最长 100 个字符")
    private String title;

    @Schema(description = "题材，如 玄幻 / 都市 / 悬疑", example = "玄幻")
    @Size(max = 64, message = "题材最长 64 个字符")
    private String genre;

    @Schema(description = "标签，最多 8 个")
    @Size(max = 8, message = "标签最多 8 个")
    private List<String> tags;

    @Schema(description = "核心创意：想写一个什么故事（必填）", example = "一个被宗门逐出的废柴，靠一本会说话的旧书重新踏上修行路")
    @NotBlank(message = "请先填写核心创意")
    @Size(max = 2000, message = "核心创意最长 2000 个字符")
    private String idea;

    @Schema(description = "风格偏好，如 轻松诙谐 / 冷硬写实", example = "热血爽文")
    @Size(max = 200, message = "风格偏好最长 200 个字符")
    private String style;

    @Schema(description = "目标篇幅，如 长篇 120 万字", example = "长篇 120 万字")
    @Size(max = 100, message = "目标篇幅最长 100 个字符")
    private String targetLength;

    @Schema(description = "主角偏好，如 想写一个女性主角", example = "主角是女性，偏冷静克制")
    @Size(max = 500, message = "主角偏好最长 500 个字符")
    private String protagonistHint;

    @Schema(description = "创作约束：建书前追问的作答，由 POST /api/bookshelf/blueprint/constraints 组装；"
            + "随蓝图落库到小说上，此后所有创作场景都会读到",
            example = "主角设定：被逐出宗门的杂役\n主角目标：找回失传的残卷")
    @Size(max = 2000, message = "创作约束最长 2000 个字符")
    private String constraintText;
}
