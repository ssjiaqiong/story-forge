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
 * 小说总纲：一段粗粒度的剧情走向，用来指导章节大纲生成。
 *
 * <p>总纲只保存标题与一段文字描述，不关联人物 / 势力 / 物品；
 * 一条总纲可被多条章节大纲引用（{@code t_chapter.master_outline_id}）。</p>
 */
@Data
@TableName("t_master_outline")
public class MasterOutlineEntity implements Serializable {

    /** 未开始：还没有为它排过章节大纲 */
    public static final String STATUS_PENDING = "PENDING";
    /** 进行中：已经在为它排大纲，或已排了大纲但故事还没讲完 */
    public static final String STATUS_RUNNING = "RUNNING";
    /** 已完成：这一段故事已经讲完，可以开下一条总纲 */
    public static final String STATUS_DONE = "DONE";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** 排序号，从 1 开始 */
    private Integer sortNo;

    private String title;

    /** 剧情走向描述 */
    private String content;

    /**
     * 推进状态：{@link #STATUS_PENDING} 未开始 / {@link #STATUS_RUNNING} 进行中 / {@link #STATUS_DONE} 已完成。
     *
     * <p>总纲是一段一段接着讲的：当前总纲没有讲完（未标 DONE），下一条不能开始。
     * 自动写书按 {@code sort_no} 升序找第一条非 DONE 的总纲推进。</p>
     */
    private String status;

    /** 预计需要多少条章节大纲；只作参考，不是硬上限（0 表示未规划） */
    private Integer plannedOutlines;

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
