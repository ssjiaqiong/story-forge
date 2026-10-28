package com.storyforge.modules.autowrite.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 新建 / 覆盖自动写书任务的入参。字段留空时按默认值兜底。
 */
@Data
@Schema(description = "自动写书任务配置")
public class AutoWriteTaskRequest {

    @Schema(description = "是否启用定时；默认 true", example = "true")
    private Boolean enabled;

    @Schema(description = "cron 表达式（6 段：秒 分 时 日 月 周）", example = "0 0 * * * ?")
    private String cronExpr;

    @Schema(description = "每次触发生成的章节数（1~20），默认 3")
    @Min(value = 1, message = "每次至少生成 1 章")
    @Max(value = 20, message = "每次最多生成 20 章")
    private Integer chaptersPerRun;

    @Schema(description = "完结口径：CHAPTERS 写到目标章数 / UNTIL_END 直到完结", example = "CHAPTERS")
    private String endMode;

    @Schema(description = "CHAPTERS 模式的目标章数；UNTIL_END 模式可传 0")
    @Min(value = 0, message = "目标章数不能为负数")
    private Integer endChapterNo;

    @Schema(description = "体检硬伤（P0）熔断阈值，默认 5")
    @Min(value = 0, message = "硬伤阈值不能为负")
    private Integer hardIssueLimit;

    @Schema(description = "体检是否跑 AI 深检；默认 true")
    private Boolean consistencyAi;

    @Schema(description = "每章目标字数，默认 3000")
    @Min(value = 300, message = "每章目标字数至少 300")
    @Max(value = 20000, message = "每章目标字数最多 20000")
    private Integer targetWordsPerChapter;
}
