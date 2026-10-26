package com.storyforge.config;

import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import com.storyforge.common.context.DataPermissionContext;
import com.storyforge.common.context.UserContextHolder;
import lombok.RequiredArgsConstructor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户级数据隔离处理器。
 *
 * <p>对 {@code storyforge.data-permission.managed-tables} 中登记的表，在 SQL 改写阶段自动追加
 * {@code user_id = <当前登录用户>} 条件；未登记的表（平台公共表）不受影响。</p>
 *
 * <p>与 Service 层的归属二次校验构成双保险：即使某处漏登记表名，Service 仍会按 ID 校验归属，
 * 越权统一返回「资源不存在」。</p>
 */
@RequiredArgsConstructor
public class UserDataPermissionHandler implements MultiDataPermissionHandler {

    private final Set<String> managedTables;

    public UserDataPermissionHandler(List<String> managedTables) {
        this.managedTables = new HashSet<>();
        if (managedTables != null) {
            managedTables.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .map(name -> name.trim().toLowerCase())
                    .forEach(this.managedTables::add);
        }
    }

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        if (managedTables.isEmpty()) {
            return null;
        }
        // 超管平台治理场景显式放行（仅当前线程、单次调用）
        if (DataPermissionContext.isIgnored()) {
            return null;
        }
        String tableName = normalize(table.getName());
        if (!managedTables.contains(tableName)) {
            return null;
        }
        Long userId = UserContextHolder.getUserIdOrNull();
        if (userId == null) {
            // 系统上下文（初始化、定时任务）不做隔离改写
            return null;
        }
        return new EqualsTo(new Column("user_id"), new LongValue(userId));
    }

    private String normalize(String rawName) {
        if (rawName == null) {
            return "";
        }
        return rawName.replace("\"", "").replace("`", "").trim().toLowerCase();
    }
}
