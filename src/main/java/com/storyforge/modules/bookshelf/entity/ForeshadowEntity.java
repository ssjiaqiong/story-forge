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
 * 伏笔台账：一条伏笔从「埋」到「收」的全过程。
 *
 * <p>伏笔只写在提示词要求里是「没有台账」的——埋了多少、收了哪些、哪条已经拖了三十章没人管，
 * 全都查不出来，读者最后的感觉就是烂尾。这张表由章节分析自动登记（埋/收都抽取），
 * 也可以由作者手工补录或放弃。</p>
 *
 * <p>超期判定：{@code plannedPayoffChapter} 有值且当前写作章已超过它，或为空且已过
 * 「埋设章 + 默认周期」，即算超期，由一致性体检的规则层报 {@code FORESHADOW}（P2，不阻断写作）。</p>
 */
@Data
@TableName("t_foreshadow")
public class ForeshadowEntity implements Serializable {

    /** 未回收 */
    public static final String STATUS_OPEN = "OPEN";
    /** 已回收 */
    public static final String STATUS_PAID = "PAID";
    /** 作者主动放弃 */
    public static final String STATUS_DROPPED = "DROPPED";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** 伏笔一句话，如「母亲留下的铜钥匙」 */
    private String title;

    /** 埋了什么、读者应该记住什么 */
    private String content;

    /** 埋设章号 */
    private Integer plantedChapterNo;

    /** 埋设章 id，便于跳转；章被删后可能悬空，展示时以章号为准 */
    private Long plantedChapterId;

    /** 计划在第几章回收；为空时按「埋设章 + 默认周期」判超期 */
    private Integer plannedPayoffChapter;

    /** {@link #STATUS_OPEN} / {@link #STATUS_PAID} / {@link #STATUS_DROPPED} */
    private String status;

    /** 实际回收章号 */
    private Integer payoffChapterNo;

    private Long payoffChapterId;

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
