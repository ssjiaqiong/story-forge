package com.storyforge.modules.ai.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 调用用量流水。
 *
 * <p>每次真实的模型调用落一行，无论成败——只有失败的也记下来，
 * 仪表盘才能区分「消耗掉的 token」和「白花的钱」。追加型表，不做逻辑删除。</p>
 */
@Data
@TableName("t_ai_usage")
public class AiUsageEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户 */
    private Long userId;

    /** {@code AiScene} 编码 */
    private String scene;

    /** 服务端实际使用的模型名 */
    private String model;

    private Integer promptTokens;

    private Integer completionTokens;

    private Integer totalTokens;

    private Integer latencyMs;

    /** 1 成功 / 0 失败 */
    private Integer success;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
}
