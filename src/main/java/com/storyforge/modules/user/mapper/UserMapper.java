package com.storyforge.modules.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.storyforge.modules.user.dto.UserQuery;
import com.storyforge.modules.user.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表 Mapper。
 *
 * <p>{@code t_user} 为平台公共表，不参与 {@code user_id} 自动隔离；超管越权由 Controller 层
 * 的 {@code @PreAuthorize} 与 Service 层业务规则共同保证。SQL 见
 * {@code resources/mapper/UserMapper.xml}。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

    /** 超管用户列表（支持关键字 / 角色 / 状态筛选，可选包含已删除）。 */
    IPage<UserEntity> selectUserPage(IPage<UserEntity> page, @Param("q") UserQuery query);

    /** 忽略逻辑删除查询（用于恢复与账号名占用检查）。 */
    UserEntity selectByIdIgnoreLogicDelete(@Param("id") Long id);

    /** 忽略逻辑删除按登录名查询。 */
    UserEntity selectAnyByUsername(@Param("username") String username);

    /** 恢复逻辑删除的账号。{@code now} 由调用方传入，避免依赖各方言不一致的数据库时钟。 */
    int restoreById(@Param("id") Long id,
                    @Param("operatorId") Long operatorId,
                    @Param("now") java.time.LocalDateTime now);

    /**
     * 记录一次登录：把当前的 {@code last_login_*} 挪到 {@code prev_login_*}，再写入本次。
     *
     * <p>用单条 UPDATE 完成，避免「先查再写」被并发登录覆盖。</p>
     */
    int touchLogin(@Param("id") Long id,
                   @Param("loginAt") java.time.LocalDateTime loginAt,
                   @Param("loginIp") String loginIp);
}
