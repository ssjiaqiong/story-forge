package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 拆书记录列表项。
 */
@Data
@Schema(description = "拆书记录")
public class DeconstructSummaryVO {

    @Schema(description = "任务 ID")
    private String taskId;

    @Schema(description = "上传文件名")
    private String sourceName;

    @Schema(description = "原文汉字数")
    private Integer sourceChars;

    @Schema(description = "改编程度")
    private String adaptationLevel;

    @Schema(description = "改编程度中文名")
    private String adaptationLabel;

    @Schema(description = "目标章节数")
    private Integer chapterTarget;

    @Schema(description = "RUNNING / SUCCEEDED / FAILED")
    private String status;

    @Schema(description = "结果书名；未完成时为空")
    private String title;

    @Schema(description = "已生成的小说 ID")
    private Long generatedNovelId;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
