package com.storyforge.modules.inspiration.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 一键创建小说的可选补充信息。
 *
 * <p>不传时完全依赖会话内容抽取；传了则以用户填的为准（用于「AI 取的标题不满意」这类微调）。</p>
 */
@Data
@Schema(description = "从灵感会话创建小说")
public class CreateNovelRequest {

    @Schema(description = "书名，留空则由 AI 从对话中提炼")
    @Size(max = 200, message = "书名最长 200 个字符")
    private String title;

    @Schema(description = "题材，留空则由 AI 从对话中提炼")
    @Size(max = 64, message = "题材最长 64 个字符")
    private String genre;
}
