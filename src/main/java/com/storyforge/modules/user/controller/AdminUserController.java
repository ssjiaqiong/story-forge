package com.storyforge.modules.user.controller;

import com.storyforge.common.api.PageResult;
import com.storyforge.common.api.R;
import com.storyforge.common.audit.Audit;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.user.dto.ResetPasswordRequest;
import com.storyforge.modules.user.dto.UserCreateRequest;
import com.storyforge.modules.user.dto.UserQuery;
import com.storyforge.modules.user.dto.UserStatusRequest;
import com.storyforge.modules.user.dto.UserUpdateRequest;
import com.storyforge.modules.user.dto.UserVO;
import com.storyforge.modules.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超级管理员「用户管理」接口。
 *
 * <p>这是平台唯一开号入口（自助注册已关闭）。超管本身仍是普通用户，此处仅额外提供账号治理能力，
 * 不涉及任何用户创作数据的内容修改。</p>
 */
@Tag(name = "超管 - 用户管理", description = "建号、编辑、启停、重置口令、逻辑删除与恢复")
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    @Operation(summary = "用户列表", description = "支持关键字 / 角色 / 状态筛选，includeDeleted=true 可列出已删除账号用于恢复")
    @GetMapping
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<PageResult<UserVO>> page(UserQuery query) {
        return R.ok(userService.page(query));
    }

    @Operation(summary = "用户详情")
    @GetMapping("/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<UserVO> detail(@PathVariable Long id) {
        return R.ok(userService.detail(id));
    }

    @Operation(summary = "创建账号", description = "新账号首次登录强制修改口令")
    @PostMapping
    @Audit(action = "USER_CREATE", targetType = "USER")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<UserVO> create(@Valid @RequestBody UserCreateRequest request) {
        return R.ok(userService.create(request));
    }

    @Operation(summary = "编辑账号", description = "修改昵称 / 角色 / 备注；字段为 null 表示不修改")
    @PutMapping("/{id}")
    @Audit(action = "USER_UPDATE", targetType = "USER", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<UserVO> update(@PathVariable Long id, @Valid @RequestBody UserUpdateRequest request) {
        return R.ok(userService.update(id, request));
    }

    @Operation(summary = "启用 / 停用账号", description = "停用后该账号令牌立即失效")
    @PutMapping("/{id}/status")
    @Audit(action = "USER_STATUS_CHANGE", targetType = "USER", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<UserVO> changeStatus(@PathVariable Long id, @Valid @RequestBody UserStatusRequest request) {
        return R.ok(userService.changeStatus(id, request.getStatus()));
    }

    @Operation(summary = "重置口令", description = "重置后该账号下次登录必须先改密")
    @PutMapping("/{id}/password")
    @Audit(action = "USER_PASSWORD_RESET", targetType = "USER", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> resetPassword(@PathVariable Long id, @Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(id, request.getNewPassword());
        return R.ok();
    }

    @Operation(summary = "删除账号", description = "逻辑删除，保留 30 天可恢复")
    @DeleteMapping("/{id}")
    @Audit(action = "USER_DELETE", targetType = "USER", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<Void> delete(@PathVariable Long id) {
        userService.delete(id);
        return R.ok();
    }

    @Operation(summary = "恢复账号")
    @PostMapping("/{id}/restore")
    @Audit(action = "USER_RESTORE", targetType = "USER", targetIdIndex = 0)
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 120, windowSeconds = 60)
    public R<UserVO> restore(@PathVariable Long id) {
        return R.ok(userService.restore(id));
    }
}
