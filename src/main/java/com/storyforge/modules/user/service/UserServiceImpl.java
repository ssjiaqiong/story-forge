package com.storyforge.modules.user.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.api.PageResult;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.config.SecurityProperties;
import com.storyforge.modules.user.dto.UserCreateRequest;
import com.storyforge.modules.user.dto.UserQuery;
import com.storyforge.modules.user.dto.UserUpdateRequest;
import com.storyforge.modules.user.dto.UserVO;
import com.storyforge.modules.user.entity.UserEntity;
import com.storyforge.modules.user.entity.UserRole;
import com.storyforge.modules.user.entity.UserStatus;
import com.storyforge.modules.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 超管用户管理服务实现。
 *
 * <p>安全护栏：</p>
 * <ol>
 *   <li>不能对当前登录账号执行停用 / 删除 / 重置口令 / 降级角色（{@code 30032}）；</li>
 *   <li>系统必须至少保留一个启用状态的超管（{@code 30033}），防止平台失去管理入口；</li>
 *   <li>登录名唯一性校验覆盖已删除记录，命中已删除账号时提示先恢复（{@code 30002}）。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final SecurityProperties securityProperties;

    @Override
    public PageResult<UserVO> page(UserQuery query) {
        long pageNo = query.normalizedPage();
        long pageSize = query.normalizedSize();
        Page<UserEntity> page = new Page<>(pageNo, pageSize);
        IPage<UserEntity> result = userMapper.selectUserPage(page, query);
        List<UserVO> records = result.getRecords().stream().map(UserVO::from).toList();
        return PageResult.of(records, result.getTotal(), pageNo, pageSize);
    }

    @Override
    public UserVO detail(Long id) {
        return UserVO.from(requireUser(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO create(UserCreateRequest request) {
        String username = request.getUsername().trim();
        UserEntity existing = userMapper.selectAnyByUsername(username);
        if (existing != null) {
            if (existing.getDeleted() != null && existing.getDeleted() == 1) {
                throw BizException.of(ErrorCode.USERNAME_RECYCLED);
            }
            throw BizException.of(ErrorCode.USERNAME_EXISTS);
        }

        String rawPassword = (request.getInitialPassword() == null || request.getInitialPassword().isBlank())
                ? securityProperties.getDefaultInitialPassword()
                : request.getInitialPassword();

        UserEntity entity = new UserEntity();
        entity.setUsername(username);
        entity.setNickname(blankToNull(request.getNickname()) == null ? username : request.getNickname().trim());
        entity.setRole(request.getRole());
        entity.setStatus(UserStatus.ACTIVE);
        entity.setPasswordHash(passwordEncoder.encode(rawPassword));
        entity.setMustChangePassword(Boolean.TRUE);
        entity.setRemark(blankToNull(request.getRemark()));
        entity.setDeleted(0);
        entity.setVersion(0);
        userMapper.insert(entity);

        log.info("[user] created userId={} username={} role={}", entity.getId(), username, request.getRole());
        return UserVO.from(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO update(Long id, UserUpdateRequest request) {
        UserEntity target = requireUser(id);

        if (request.getRole() != null && request.getRole() != target.getRole()) {
            if (isSelf(id)) {
                throw BizException.of(ErrorCode.SELF_OPERATION_FORBIDDEN, "不能修改自己的角色");
            }
            if (target.getRole() == UserRole.SUPER_ADMIN && request.getRole() == UserRole.USER) {
                ensureNotLastActiveSuperAdmin();
            }
        }

        UserEntity update = new UserEntity();
        update.setId(id);
        update.setNickname(blankToNull(request.getNickname()));
        update.setRole(request.getRole());
        update.setRemark(request.getRemark());
        update.setVersion(target.getVersion());
        userMapper.updateById(update);

        log.info("[user] updated userId={}", id);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO changeStatus(Long id, UserStatus status) {
        UserEntity target = requireUser(id);
        if (isSelf(id)) {
            throw BizException.of(ErrorCode.SELF_OPERATION_FORBIDDEN, "不能停用当前登录账号");
        }
        if (status == UserStatus.DISABLED && target.getRole() == UserRole.SUPER_ADMIN) {
            ensureNotLastActiveSuperAdmin();
        }

        UserEntity update = new UserEntity();
        update.setId(id);
        update.setStatus(status);
        update.setVersion(target.getVersion());
        userMapper.updateById(update);

        log.info("[user] status changed userId={} -> {}", id, status);
        return detail(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newPassword) {
        UserEntity target = requireUser(id);
        if (isSelf(id)) {
            throw BizException.of(ErrorCode.SELF_OPERATION_FORBIDDEN, "请使用「修改口令」修改自己的口令");
        }

        UserEntity update = new UserEntity();
        update.setId(id);
        update.setPasswordHash(passwordEncoder.encode(newPassword));
        update.setMustChangePassword(Boolean.TRUE);
        update.setVersion(target.getVersion());
        userMapper.updateById(update);

        log.info("[user] password reset userId={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        UserEntity target = requireUser(id);
        if (isSelf(id)) {
            throw BizException.of(ErrorCode.SELF_OPERATION_FORBIDDEN, "不能删除当前登录账号");
        }
        if (target.getRole() == UserRole.SUPER_ADMIN) {
            ensureNotLastActiveSuperAdmin();
        }
        userMapper.deleteById(id);
        log.info("[user] logically deleted userId={} username={}", id, target.getUsername());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO restore(Long id) {
        UserEntity any = userMapper.selectByIdIgnoreLogicDelete(id);
        if (any == null) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND);
        }
        if (any.getDeleted() == null || any.getDeleted() == 0) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "该账号未被删除，无需恢复");
        }
        UserEntity active = userMapper.selectOne(
                Wrappers.<UserEntity>lambdaQuery().eq(UserEntity::getUsername, any.getUsername()));
        if (active != null && !active.getId().equals(id)) {
            throw BizException.of(ErrorCode.USERNAME_EXISTS);
        }
        userMapper.restoreById(id, UserContextHolder.getUserIdOrNull(), java.time.LocalDateTime.now());
        log.info("[user] restored userId={} username={}", id, any.getUsername());
        return detail(id);
    }

    /* ------------------------------------------------------------------ */

    private UserEntity requireUser(Long id) {
        UserEntity user = userMapper.selectById(id);
        if (user == null) {
            throw BizException.of(ErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    private boolean isSelf(Long id) {
        Long current = UserContextHolder.getUserIdOrNull();
        return current != null && current.equals(id);
    }

    /** 保证系统至少保留一个启用状态的超管。 */
    private void ensureNotLastActiveSuperAdmin() {
        Long count = userMapper.selectCount(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getRole, UserRole.SUPER_ADMIN)
                .eq(UserEntity::getStatus, UserStatus.ACTIVE));
        if (count == null || count <= 1L) {
            throw BizException.of(ErrorCode.LAST_SUPER_ADMIN);
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
