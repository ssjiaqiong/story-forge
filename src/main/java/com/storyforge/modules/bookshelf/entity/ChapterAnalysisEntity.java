package com.storyforge.modules.bookshelf.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 章节分析报告。
 *
 * <p>{@code resultJson} 存完整报告（评分理由、本地检测信号、变更明细），
 * 常用评分另存数值列，列表查询不必反序列化 JSON。</p>
 */
@Data
@TableName("t_chapter_analysis")
public class ChapterAnalysisEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long chapterId;

    private Integer chapterNo;

    private String chapterTitle;

    /** 本章汉字数 */
    private Integer wordCount;

    /** 目标字数（分析时确定） */
    private Integer targetWords;

    /** 1 达标 0 不达标 */
    private Integer wordPass;

    /** 大纲贴合度，0~100 */
    private Integer outlineScore;

    /** AI 率，0~100，0 为纯 AI 味 */
    private Integer aiScore;

    private String model;

    private Long latencyMs;

    /** 完整报告 JSON */
    private String resultJson;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @Version
    private Integer version;
}
