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
 * 要素变更履历：人物 / 物品 / 势力的状态变迁。
 *
 * <p>一条记录只描述「一个字段在一个章节里的一次变化」，原状态取自分析前的库内值，
 * 现状态由模型读完本章后给出，二者都带正文依据，便于作者回查。</p>
 */
@Data
@TableName("t_change_history")
public class ChangeHistoryEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long chapterId;

    private Integer chapterNo;

    private String chapterTitle;

    /** 产生这次履历的分析记录 */
    private Long analysisId;

    /** CHARACTER / ITEM / FACTION */
    private String entityType;

    private Long entityId;

    private String entityName;

    /** lifeStatus / identity / owner / status / quantity / scope 等 */
    private String fieldKey;

    private String fieldLabel;

    private String beforeState;

    private String afterState;

    private String reason;

    private String evidence;

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
