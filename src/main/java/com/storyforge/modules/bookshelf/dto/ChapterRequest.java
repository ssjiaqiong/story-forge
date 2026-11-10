package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 章节新增 / 修改入参。字段为空表示「不修改」，新增时由 Service 补默认值。
 */
@Data
@Schema(description = "章节入参")
public class ChapterRequest {

    @Schema(description = "卷序号，从 1 开始")
    @Min(value = 1, message = "卷序号至少为 1")
    private Integer volumeNo;

    @Schema(description = "卷名")
    @Size(max = 120, message = "卷名最长 120 个字符")
    private String volumeTitle;

    @Schema(description = "章节号")
    @Min(value = 1, message = "章节号至少为 1")
    private Integer chapterNo;

    @Schema(description = "章节标题")
    @Size(max = 200, message = "章节标题最长 200 个字符")
    private String title;

    @Schema(description = "大纲摘要：本章要写什么")
    private String summary;

    @Schema(description = "正文内容")
    private String content;

    @Schema(description = "状态：DRAFT / WRITING / DONE")
    @Pattern(regexp = "DRAFT|WRITING|DONE", message = "章节状态非法")
    private String status;

    @Schema(description = "本章参与人物 ID 列表；null 表示不修改，空数组表示清空")
    private List<Long> participantIds;

    @Schema(description = "本章关联事件 ID 列表；null 表示不修改，空数组表示清空")
    private List<Long> eventIds;

    @Schema(description = "关联的总纲 id；null 表示不修改，0 表示解除关联")
    private Long masterOutlineId;

    /**
     * 是否允许覆盖「已完成」章节的正文与标题。
     *
     * <p>默认 false：定稿章节照旧拦截（`40061`）。只有作者在界面上明确确认
     * 「当前章节已完成，是否覆盖」之后，前端才会带 true 重发一次——
     * 这个开关是**一次请求**的授权，不是给章节解锁，章节状态不会被改动。</p>
     */
    @Schema(description = "作者已确认覆盖已完成章节：true 时放行对定稿正文/标题的修改，默认 false")
    private Boolean overwriteDone;
}
