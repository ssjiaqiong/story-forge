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
 *
 * <p>{@code factSummary} 是**实际发生了什么**——由章节分析从正文里抽取，
 * 与 {@code summary}（计划）语义不同、互不覆盖。后续章节拼装「前情」时优先用它，
 * 为空才回退 {@code summary}。这是长篇前后一致的地基：正文偏离大纲后，
 * 靠 summary 理解前情会让偏差逐章累积。</p>
 */
@Data
@TableName("t_chapter")
public class ChapterEntity implements Serializable {

    /** 事实摘要来源：章节分析产出 */
    public static final String FACT_SOURCE_AI = "AI";
    /** 事实摘要来源：作者手工编辑（之后 AI 不再覆盖） */
    public static final String FACT_SOURCE_MANUAL = "MANUAL";
    /** 事实摘要来源：存量章节回填 */
    public static final String FACT_SOURCE_BACKFILL = "BACKFILL";

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

    /** 大纲摘要：这一章**要写什么** */
    private String summary;

    /** 事实摘要：这一章**实际写了什么**（150~250 字），为空表示还没分析或分析没产出 */
    private String factSummary;

    /** 事实摘要来源：AI / MANUAL / BACKFILL */
    private String factSource;

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
