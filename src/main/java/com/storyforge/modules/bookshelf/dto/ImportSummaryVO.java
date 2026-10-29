package com.storyforge.modules.bookshelf.dto;

import lombok.Data;

/**
 * 整本导入记录列表项。
 */
@Data
public class ImportSummaryVO {

    /** 任务 ID */
    private String taskId;

    private String sourceName;

    private String title;

    private Integer sourceChars;

    private Integer chapterCount;

    private String status;

    private Long novelId;

    private String createdAt;

    private String errorMessage;
}
