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
 * 小说的卷（分卷）。
 *
 * <p><b>与章节冗余字段的关系</b>：章节表保留 {@code volume_no} 与 {@code volume_title}，
 * 它们是「卷 → 章节」的单向投影，只能由 {@code VolumeService} 写。这样列表、导出、管理端、
 * 拆书预览全都还按 {@code volume_no} 分组，不必为了卷的去重与回填改一堆查询。</p>
 *
 * <p>代价是两处数据的一致性要靠服务层保证：改卷名必须同步 {@code t_chapter.volume_title}，
 * 换卷序必须同步 {@code t_chapter.volume_no} 与 {@code sort_no}。这些动作只在
 * {@code VolumeService} 里出现，别处一律不许直接写章节的卷字段。</p>
 */
@Data
@TableName("t_volume")
public class VolumeEntity implements Serializable {

    /** 卷名缺省时的兜底前缀，导出与列表用它拼「第 N 卷」 */
    public static final String DEFAULT_TITLE_PREFIX = "第 ";
    public static final String DEFAULT_TITLE_SUFFIX = " 卷";

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    /** 卷序号，从 1 开始 */
    private Integer volumeNo;

    private String title;

    /** 卷简介，可空 */
    private String intro;

    /** 与 volumeNo 同值 */
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

    /** 卷名没填时的显示名。 */
    public static String fallbackTitle(Integer volumeNo) {
        int no = volumeNo == null ? 1 : volumeNo;
        return DEFAULT_TITLE_PREFIX + no + DEFAULT_TITLE_SUFFIX;
    }
}
