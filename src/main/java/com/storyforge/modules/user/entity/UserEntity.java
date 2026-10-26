package com.storyforge.modules.user.entity;

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
 * 用户（平台唯一账号形态，也是最高数据隔离单元）。
 *
 * <p>平台公共表，不参与 {@code user_id} 自动隔离。</p>
 */
@Data
@TableName("t_user")
public class UserEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录名，未删除记录内唯一 */
    private String username;

    /** BCrypt 散列 */
    private String passwordHash;

    private String nickname;

    /** {@link UserRole} */
    private UserRole role;

    /** {@link UserStatus} */
    private UserStatus status;

    /** 是否必须先修改口令（新建 / 重置后为 true） */
    private Boolean mustChangePassword;

    /** 备注 */
    private String remark;

    private LocalDateTime lastLoginAt;

    private String lastLoginIp;

    /** 上一次登录时间（本次登录时由 {@code last_login_at} 挪入） */
    private LocalDateTime prevLoginAt;

    /** 上一次登录 IP */
    private String prevLoginIp;

    /** 逻辑删除标记：0 未删除，1 已删除（保留 30 天可恢复） */
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

    /** 乐观锁版本 */
    @Version
    private Integer version;
}
