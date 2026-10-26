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
 * 角色关系。
 *
 * <p>关系语义为对称：写入前两端 id 会按大小排序，因此 {@code fromCharacterId} 恒小于
 * {@code toCharacterId}，展示时不必关心方向。</p>
 */
@Data
@TableName("t_character_relation")
public class CharacterRelationEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private Long fromCharacterId;

    private Long toCharacterId;

    /** MASTER_APPRENTICE / FAMILY / SPOUSE / LOVER / FRIEND / ALLY / ENEMY / RIVAL / COLLEAGUE / OTHER */
    private String relationType;

    private String description;

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
