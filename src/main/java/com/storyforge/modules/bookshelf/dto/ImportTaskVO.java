package com.storyforge.modules.bookshelf.dto;

import lombok.Data;

import java.util.List;

/**
 * 整本导入任务的状态与结果。
 *
 * <p>前端只看这一个对象：进度条读 percent / stepLabel，完成后读 novelId 跳转工作台，
 * 摘要（summary）用于在结果页显示「逆推出来多少人物、物品、势力」。</p>
 */
@Data
public class ImportTaskVO {

    /** 任务 ID（雪花 ID 以字符串下发） */
    private String taskId;

    /** RUNNING / SUCCEEDED / FAILED */
    private String status;

    private Integer percent;

    private Integer stepNo;

    private Integer stepTotal;

    private String stepLabel;

    private String errorMessage;

    /** 上传文件名 */
    private String sourceName;

    /** 生成的书名 */
    private String title;

    private String penName;

    /** AI 判定的题材 */
    private String genre;

    /** 原文汉字数 */
    private Integer sourceChars;

    /** 识别出的章节数 */
    private Integer chapterCount;

    /** 已落库章节数 */
    private Integer importedChapters;

    /** 生成的小说 ID；非空即可跳转 */
    private Long novelId;

    private String createdAt;

    /** 逆推结果摘要，仅 SUCCEEDED 时有值 */
    private Summary summary;

    /**
     * 逆推摘要。
     */
    @Data
    public static class Summary {
        private Integer characters;
        private Integer relations;
        private Integer factions;
        private Integer items;
        private Integer settings;
        private Integer masterOutlines;
        private Integer chapterOutlines;
        /** 通读段数 / 是否做了抽样（长书只读头中尾） */
        private Integer readSegments;
        private Boolean sampled;
        private String model;
        private Long latencyMs;
        /** 原文明细口径提示，如「原文 102 万字，通读 12 段（抽样）」 */
        private List<String> notes;
    }
}
