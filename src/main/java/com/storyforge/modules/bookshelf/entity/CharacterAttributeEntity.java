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
 * 人物属性：只有「当前值」。
 *
 * <p>历史变化不在这里记流水，统一由 {@code t_change_history}（章节分析写入）承担。</p>
 */
@Data
@TableName("t_character_attribute")
public class CharacterAttributeEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long characterId;

    /** 属性名：体质 / 积分 / 存款 / 人气 / 等级 */
    private String name;

    /** 当前值，字符串以兼容「筑基三层」这类文本值 */
    private String value;

    private String unit;

    /** NUMBER 数值 / TEXT 文本 */
    private String attrType;

    private String note;

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
