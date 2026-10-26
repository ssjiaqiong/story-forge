package com.storyforge.modules.search.entity;

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
 * 搜索场景绑定：决定「事件生成 / 大纲生成 / 正文生成」分别使用哪个搜索源。
 *
 * <p>没有记录时按「启用 + 默认搜索源 + 默认条数」处理，所以新用户无需配置即可用。</p>
 */
@Data
@TableName("t_search_scene")
public class SearchSceneEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 复用 AiScene 编码：EVENT_SUPPLY / OUTLINE_GEN / BODY_GEN */
    private String sceneCode;

    /** 为空表示继承默认搜索源 */
    private Long configId;

    private Boolean enabled;

    /** 为空表示使用搜索源的默认条数 */
    private Integer topK;

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
