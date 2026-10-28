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
 * 章节正文的历史版本。
 *
 * <p>语义是「<b>保存前</b>的旧内容」：每次正文发生变化时，把库里那份先存下来，
 * 当前稿永远只存在 {@code t_chapter.content}。因此「回滚」就是把这个版本写回去，
 * 「历史列表」天然只有旧稿，不需要双写。</p>
 */
@Data
@TableName("t_chapter_version")
public class ChapterVersionEntity implements Serializable {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_AI_BODY = "AI_BODY";
    public static final String SOURCE_AI_REVISE = "AI_REVISE";
    public static final String SOURCE_BATCH = "BATCH";
    public static final String SOURCE_RESTORE = "RESTORE";
    public static final String SOURCE_IMPORT = "IMPORT";
    public static final String SOURCE_AUTO = "AUTO";
    /** 自动写书流水线写回正文 */
    public static final String SOURCE_AUTO_WRITE = "AUTO_WRITE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long chapterId;

    /** 章内递增，从 1 开始 */
    private Integer versionNo;

    /** MANUAL / AI_BODY / AI_REVISE / BATCH / RESTORE / IMPORT / AUTO */
    private String source;

    /** 当时的章节标题 */
    private String title;

    /** 快照正文 */
    private String content;

    private Integer wordCount;

    /** 备注，如「回滚自第 3 版」 */
    private String note;

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
