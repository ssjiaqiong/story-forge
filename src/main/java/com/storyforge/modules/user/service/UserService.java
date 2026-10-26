package com.storyforge.modules.user.service;

import com.storyforge.common.api.PageResult;
import com.storyforge.modules.user.dto.UserCreateRequest;
import com.storyforge.modules.user.dto.UserQuery;
import com.storyforge.modules.user.dto.UserUpdateRequest;
import com.storyforge.modules.user.dto.UserVO;
import com.storyforge.modules.user.entity.UserStatus;

/**
 * 超管用户管理服务（平台级功能）。
 */
public interface UserService {

    /** 用户列表（分页 + 关键字 / 角色 / 状态筛选，可选包含已删除）。 */
    PageResult<UserVO> page(UserQuery query);

    /** 用户详情。 */
    UserVO detail(Long id);

    /** 创建账号（关闭注册后的唯一开号路径），新账号首登强制改密。 */
    UserVO create(UserCreateRequest request);

    /** 编辑账号基本信息（昵称 / 角色 / 备注）。 */
    UserVO update(Long id, UserUpdateRequest request);

    /** 启用 / 停用账号。停用后令牌立即失效。 */
    UserVO changeStatus(Long id, UserStatus status);

    /** 重置口令，重置后要求下次登录改密。 */
    void resetPassword(Long id, String newPassword);

    /** 逻辑删除账号（保留 30 天可恢复）。 */
    void delete(Long id);

    /** 恢复逻辑删除的账号。 */
    UserVO restore(Long id);
}
