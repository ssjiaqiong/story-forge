package com.storyforge.modules.auth.dto;

import com.storyforge.modules.user.dto.UserVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录 / 刷新令牌返回体。
 */
@Data
public class LoginResponse implements Serializable {

    @Schema(description = "访问令牌，放入 Authorization: Bearer {token}")
    private String accessToken;

    @Schema(description = "刷新令牌，用于换取新的访问令牌（一次性，轮换后旧的失效）")
    private String refreshToken;

    @Schema(description = "令牌类型")
    private String tokenType = "Bearer";

    @Schema(description = "访问令牌有效期（秒）")
    private long expiresIn;

    @Schema(description = "当前登录用户信息")
    private UserVO user;
}
