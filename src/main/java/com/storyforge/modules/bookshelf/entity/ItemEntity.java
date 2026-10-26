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
 * 小说物品道具：法宝 / 信物 / 关键线索等。
 */
@Data
@TableName("t_item")
public class ItemEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private String name;

    /** 类别：法宝 / 信物 / 线索 / 消耗品 */
    private String category;

    /** 当前持有者 */
    private String owner;

    /** 物品描述：外貌形态、材质、尺寸、磨损标记、特殊特征与象征意义 */
    private String description;

    /** 说明：来历、经手人、关联的关键事件或秘密 */
    private String origin;

    /** ACTIVE 活跃 / DESTROYED 摧毁 / ABANDONED 废弃 / LOST 遗失 / CONSUMED 已消耗 */
    private String status;

    /** 数量，可空（唯一或不可计数物品不填） */
    private Integer quantity;

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
