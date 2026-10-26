package com.storyforge.common.security;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 令牌黑名单（登出 / 轮换后失效的 jti）。
 *
 * <p>无 Redis 约束下用 PostgreSQL 承载；{@code expires_at} 支撑定时清理。</p>
 */
@Data
@TableName("t_token_blacklist")
public class TokenBlacklistEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 令牌唯一标识 */
    private String jti;

    private Long userId;

    /** access / refresh */
    private String tokenType;

    /** 令牌自身的过期时间，到点即可清理 */
    private LocalDateTime expiresAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
