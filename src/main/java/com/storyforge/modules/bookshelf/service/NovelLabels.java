package com.storyforge.modules.bookshelf.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 小说要素的中文标签。
 *
 * <p>分析报告、变更履历都要把枚举翻译成人话，统一放这里，避免各处各写一套。</p>
 */
public final class NovelLabels {

    private NovelLabels() {
    }

    public static final Map<String, String> ROLE = ordered(
            "PROTAGONIST", "主角", "SUPPORTING", "配角", "ANTAGONIST", "反派", "MINOR", "龙套");

    /** 小说状态：书架卡片、仪表盘、超管列表共用同一套中文 */
    public static final Map<String, String> NOVEL_STATUS = ordered(
            "DRAFT", "草稿", "WRITING", "连载中", "FINISHED", "已完结", "ARCHIVED", "已归档");

    /** 章节状态 */
    public static final Map<String, String> CHAPTER_STATUS = ordered(
            "DRAFT", "草稿", "WRITING", "写作中", "DONE", "已完成");

    public static final Map<String, String> LIFE = ordered(
            "ALIVE", "存活", "DEAD", "死亡", "MISSING", "失踪", "UNKNOWN", "未知");

    public static final Map<String, String> ITEM_STATUS = ordered(
            "ACTIVE", "活跃", "DESTROYED", "摧毁", "ABANDONED", "废弃", "LOST", "遗失", "CONSUMED", "已消耗");

    public static final Map<String, String> FACTION_STATUS = ordered(
            "ACTIVE", "活跃", "DORMANT", "蛰伏", "DESTROYED", "覆灭", "DISBANDED", "解散");

    public static final Map<String, String> EVENT_STATUS = ordered(
            "UNUSED", "未使用", "ONGOING", "进行中", "DONE", "已完成", "ABANDONED", "废弃");

    public static final Map<String, String> INVENTORY_STATUS = ordered(
            "HELD", "持有", "LOST", "遗失", "CONSUMED", "已消耗");

    public static final Map<String, String> INVENTORY_ENTRY = ordered(
            "ITEM", "物品", "CURRENCY", "货币", "MISC", "物资");

    public static final Map<String, String> ENTITY_TYPE = ordered(
            "CHARACTER", "人物", "ITEM", "物品", "FACTION", "势力",
            "CHARACTER_ATTRIBUTE", "属性", "INVENTORY", "背包");

    /** 各类型允许变更的字段 */
    public static final Map<String, Map<String, String>> FIELDS = Map.of(
            "CHARACTER", ordered(
                    "lifeStatus", "生死状态", "identity", "身份职位", "faction", "所属势力", "rolePos", "角色定位"),
            "ITEM", ordered(
                    "status", "物品状态", "owner", "持有者", "quantity", "数量"),
            "FACTION", ordered(
                    "status", "势力状态", "scope", "范围"));

    public static String label(Map<String, String> map, String key) {
        if (key == null) {
            return "";
        }
        return map.getOrDefault(key, key);
    }

    public static String entityTypeLabel(String entityType) {
        return label(ENTITY_TYPE, entityType);
    }

    public static String fieldLabel(String entityType, String fieldKey) {
        // 属性与背包的字段是用户自定义的名称，没有固定枚举，统一加前缀便于阅读
        if ("CHARACTER_ATTRIBUTE".equals(entityType)) {
            return "属性·" + fieldKey;
        }
        if ("INVENTORY".equals(entityType)) {
            return "背包·" + fieldKey;
        }
        Map<String, String> fields = FIELDS.get(entityType);
        if (fields == null) {
            return fieldKey;
        }
        return label(fields, fieldKey);
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }
}
