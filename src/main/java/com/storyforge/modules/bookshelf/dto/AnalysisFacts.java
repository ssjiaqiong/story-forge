package com.storyforge.modules.bookshelf.dto;

import java.util.List;

/**
 * 模型从本章抽取到的「事实」。
 *
 * <p>字段名与提示词里的 JSON 一一对应；解析后由服务端按名字映射成 ID，
 * 映射不上的直接丢弃，不污染档案。</p>
 */
public record AnalysisFacts(
        List<NewCharacter> newCharacters,
        List<Change> characterChanges,
        List<Change> itemChanges,
        List<Change> factionChanges,
        List<EventChange> eventChanges,
        List<Change> attributeChanges,
        List<Change> inventoryChanges) {

    public static AnalysisFacts empty() {
        return new AnalysisFacts(List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    /** 本章首次出场的人物 */
    public record NewCharacter(String name,
                               String identity,
                               String rolePos,
                               String gender,
                               String ageDesc,
                               String appearance,
                               String personality,
                               String motivation,
                               String reason) {
    }

    /**
     * 一条要素变更：谁、哪个字段、变成什么。
     *
     * <p>属性与背包复用同一个结构：{@code name} 是角色姓名，
     * {@code field} 是属性名 / 背包条目名，{@code after} 是新的值或数量。</p>
     */
    public record Change(String name, String field, String after, String reason, String evidence) {
    }

    /** 关联事件的状态变化 */
    public record EventChange(String title, String status, String reason, String evidence) {
    }
}
