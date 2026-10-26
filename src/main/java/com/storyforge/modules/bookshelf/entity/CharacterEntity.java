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
 * 小说角色。
 *
 * <p>结构化字段（姓名 / 身份 / 定位）单独成列便于后续检索与流水线引用，
 * 其余自由描述放在 {@code profile_json}。</p>
 */
@Data
@TableName("t_character")
public class CharacterEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long novelId;

    private String name;

    private String alias;

    private String gender;

    private String ageDesc;

    /** 身份职位，如「青云宗外门弟子」 */
    private String identity;

    /** PROTAGONIST / SUPPORTING / ANTAGONIST / MINOR */
    private String rolePos;

    /** ALIVE / DEAD / MISSING / UNKNOWN */
    private String lifeStatus;

    private Integer firstChapter;

    private Long factionId;

    /** 扩展设定 JSON：personality / motivation / goldenFinger / arc / appearance */
    private String profileJson;

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
