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
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 人物背包条目。
 *
 * <p>三型：{@code ITEM} 关联物品档案（复用其描述与说明）、
 * {@code CURRENCY} 货币（铜钱 / 灵石 / 元）、{@code MISC} 常规物资（干粮、疗伤丹）。</p>
 */
@Data
@TableName("t_character_inventory")
public class CharacterInventoryEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long characterId;

    /** ITEM / CURRENCY / MISC */
    private String entryType;

    /** entryType=ITEM 时指向 t_item */
    private Long itemId;

    private String name;

    /** 数量或金额；可为空（表示"若干"） */
    private BigDecimal quantity;

    private String unit;

    /** HELD 持有 / LOST 遗失 / CONSUMED 已消耗 */
    private String status;

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
