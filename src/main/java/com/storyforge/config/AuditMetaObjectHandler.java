package com.storyforge.config;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.storyforge.common.context.UserContextHolder;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 通用字段自动填充。
 *
 * <p>插入时填充 {@code createdAt/createdBy/updatedAt/updatedBy}，若实体存在 {@code userId}
 * 字段则同时填充归属用户，保证数据隔离字段不会被业务代码遗漏。</p>
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = UserContextHolder.getUserIdOrNull();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        if (userId != null) {
            strictInsertFill(metaObject, "createdBy", Long.class, userId);
            strictInsertFill(metaObject, "updatedBy", Long.class, userId);
            strictInsertFill(metaObject, "userId", Long.class, userId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
        Long userId = UserContextHolder.getUserIdOrNull();
        if (userId != null) {
            strictUpdateFill(metaObject, "updatedBy", Long.class, userId);
        }
    }
}
