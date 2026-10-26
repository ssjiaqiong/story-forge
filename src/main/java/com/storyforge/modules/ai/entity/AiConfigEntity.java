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
 * AI 接口配置（OpenAI 协议端点）。
 *
 * <p>{@code user_id} 为数据隔离键，查询由 MyBatis-Plus 数据权限插件自动追加条件；
 * 写入时由 Service 显式赋值。</p>
 */
@Data
@TableName("t_ai_config")
public class AiConfigEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** 配置名称，同一用户内唯一 */
    private String name;

    /** 接口类型：TEXT 文本 / IMAGE 图像（文生图），创建后不可变更 */
    private String kind;

    /** 平台协议（文生图）：OPENAI / SILICONFLOW / VOLCENGINE / ZHIPU / DASHSCOPE / MODELSCOPE */
    private String provider;

    /** 图像接口默认出图尺寸，形如 1024x1536；文本接口为空 */
    private String imageSize;

    /** 接口基址，含版本段，如 https://api.openai.com/v1 */
    private String baseUrl;

    /** API Key 密文（AES-256-GCM），禁止明文 */
    private String apiKeyCipher;

    /** API Key 掩码，仅用于回显 */
    private String apiKeyMask;

    /** 默认模型 */
    private String defaultModel;

    /** 模型列表缓存（JSON 数组文本） */
    private String modelList;

    private Integer timeoutMs;

    private Double temperature;

    private Integer maxTokens;

    private Double topP;

    /** 附加请求头（JSON 对象文本） */
    private String extraHeaders;

    private Boolean enabled;

    /** 是否为该用户该类型（文本 / 图像）的默认端点 */
    private Boolean isDefault;

    private String remark;

    private LocalDateTime lastTestAt;

    private Boolean lastTestOk;

    private Integer lastTestLatencyMs;

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
