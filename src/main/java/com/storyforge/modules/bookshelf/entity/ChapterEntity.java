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
 * 小说章节：大纲与正文共用。
 *
 * <p>{@code summary} 是大纲摘要（写什么），{@code content} 是正文，二者分开保存，
 * 便于「大纲」菜单只读摘要、不拉正文。</p>
 */
@Data
@TableName("t_chapter")
public class ChapterEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** 所属卷序号，从 1 开始 */
    private Integer volumeNo;

    /** 卷名，如「第一卷 旧书」 */
    private String volumeTitle;

    private Integer chapterNo;

    private String title;

    /** 大纲摘要 */
    private String summary;

    /** 正文 */
    private String content;

    private Integer wordCount;

    /** DRAFT / WRITING / DONE */
    private String status;

    /** 关联的总纲 id：本章大纲属于哪一段剧情走向，可空 */
    private Long masterOutlineId;

    private Integer sortNo;

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
