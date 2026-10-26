package com.storyforge.modules.ai.entity;

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
 * 场景级模型绑定。
 *
 * <p>未绑定的场景回落到用户默认 AI 配置及其默认参数。</p>
 */
@Data
@TableName("t_scene_binding")
public class SceneBindingEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 场景编码，取值见 {@link com.storyforge.modules.ai.scene.AiScene} */
    private String sceneCode;

    private Long aiConfigId;

    /** 为空表示沿用端点默认模型 */
    private String model;

    /** 为空表示沿用端点默认温度 */
    private Double temperature;

    /** 为空表示沿用端点默认 max_tokens */
    private Integer maxTokens;

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
