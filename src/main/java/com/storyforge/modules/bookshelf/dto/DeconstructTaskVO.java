package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 拆书任务视图（进度轮询与结果预览共用）。
 */
@Data
@Schema(description = "拆书任务")
public class DeconstructTaskVO {

    @Schema(description = "任务 ID")
    private String taskId;

    @Schema(description = "RUNNING / SUCCEEDED / FAILED")
    private String status;

    @Schema(description = "进度百分比")
    private Integer percent;

    @Schema(description = "当前步骤序号")
    private Integer stepNo;

    @Schema(description = "总步骤数")
    private Integer stepTotal;

    @Schema(description = "当前阶段文案")
    private String stepLabel;

    @Schema(description = "提示信息，如某段解析失败正在重试")
    private String message;

    @Schema(description = "失败原因")
    private String errorMessage;

    @Schema(description = "上传文件名")
    private String sourceName;

    @Schema(description = "原文汉字数")
    private Integer sourceChars;

    @Schema(description = "原文识别章节数")
    private Integer sourceChapters;

    @Schema(description = "改编程度")
    private String adaptationLevel;

    @Schema(description = "背景改编方向")
    private String backgroundDirection;

    @Schema(description = "目标章节数")
    private Integer chapterTarget;

    @Schema(description = "改编结果；RUNNING 时为空")
    private DeconstructResult result;

    @Schema(description = "已保存成的小说 ID")
    private Long generatedNovelId;
}
