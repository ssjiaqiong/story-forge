package com.storyforge.modules.inspiration.entity;

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
 * 灵感技能：每轮对话的 system 提示词来源。
 *
 * <p>内置技能在用户首次进入灵感模式时复制到该用户名下，之后对该副本的修改只影响本人。</p>
 */
@Data
@TableName("t_inspiration_skill")
public class InspirationSkillEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 技能编码；内置技能固定为 NOVEL_CREATE */
    private String code;

    private String name;

    private String description;

    /** 系统提示词 */
    private String systemPrompt;

    /** 内置技能：可改、可恢复默认，不可删除 */
    private Boolean builtin;

    private Boolean enabled;

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
