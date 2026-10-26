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
 * 拆书记录。
 *
 * <p>只保存原文的文件名、字数与章节数，<b>不保存原文正文</b>；分析结果统一序列化在
 * {@code resultJson} 中，供前端预览「主线脉络 + 改编后的设定 / 人物 / 势力 / 大纲」。</p>
 */
@Data
@TableName("t_book_digest")
public class BookDigestEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 上传文件名 */
    private String sourceName;

    /** 原文汉字数 */
    private Integer sourceChars;

    /** 原文识别出的章节数 */
    private Integer sourceChapters;

    /** 改编程度：LIGHT / MEDIUM / HEAVY / REBUILD */
    private String adaptationLevel;

    /** 背景改编方向，用户填写，可空 */
    private String backgroundDirection;

    /** 目标章节数（大纲规模） */
    private Integer chapterTarget;

    /** RUNNING / SUCCEEDED / FAILED */
    private String status;

    private Integer percent;

    private Integer stepNo;

    private Integer stepTotal;

    private String stepLabel;

    /** 改编结果 JSON */
    private String resultJson;

    /** 改编后的书名，列表展示用，避免解析大 JSON */
    private String resultTitle;

    private String errorMessage;

    /** 已保存成的小说 ID，非空即不可重复保存 */
    private Long generatedNovelId;

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
