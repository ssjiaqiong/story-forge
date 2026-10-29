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
 * 整本 TXT 导入任务。
 *
 * <p>与拆书（{@link BookDigestEntity}）的区别：拆书只读原文、不落库，产出的是<b>改编后</b>的大纲；
 * 导入要把原文<b>一字不差</b>按章落到 {@code t_chapter.content}，再用 AI 逆推出设定、人物、物品、
 * 势力、总纲与逐章大纲，使这本书可以像自己写的一样继续续写与编辑。</p>
 */
@Data
@TableName("t_novel_import")
public class NovelImportEntity implements Serializable {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 上传文件名 */
    private String sourceName;

    /** 导入后生成的书名 */
    private String title;

    /** 笔名，用户填写，可空 */
    private String penName;

    /** 题材，由 AI 判定 */
    private String genre;

    /** 原文汉字数 */
    private Integer sourceChars;

    /** 识别出的章节总数 */
    private Integer chapterCount;

    /** 卷数 */
    private Integer volumeCount;

    /** 已落库的章节数 */
    private Integer importedChapters;

    /** RUNNING / SUCCEEDED / FAILED */
    private String status;

    private Integer percent;

    private Integer stepNo;

    private Integer stepTotal;

    private String stepLabel;

    /** 逆推结果摘要 JSON：各资产条数、模型与耗时 */
    private String resultJson;

    private String errorMessage;

    /** 生成的小说 ID */
    private Long novelId;

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
