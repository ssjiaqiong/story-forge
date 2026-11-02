package com.storyforge.modules.bookshelf.dto;

import java.util.List;

/**
 * 模型从本章抽取到的「事实」。
 *
 * <p>字段名与提示词里的 JSON 一一对应；解析后由服务端按名字映射成 ID，
 * 映射不上的直接丢弃，不污染档案。</p>
 *
 * <p>除了「谁变了什么」这类字段级变更，还产出四类**叙事层事实**：
 * {@code factSummary}（本章实际发生了什么）、{@code characterStates}（位置 / 身体 / 认知）、
 * {@code foreshadowPlanted}（新埋的伏笔）、{@code foreshadowPaid}（本章回收的伏笔）。
 * 前七类喂档案，这四类喂「下一章的上下文」——长篇能不能前后一致，靠的是后者。</p>
 */
public record AnalysisFacts(
        String factSummary,
        List<NewCharacter> newCharacters,
        List<Change> characterChanges,
        List<Change> itemChanges,
        List<Change> factionChanges,
        List<EventChange> eventChanges,
        List<Change> attributeChanges,
        List<Change> inventoryChanges,
        List<CharacterState> characterStates,
        List<ForeshadowPlanted> foreshadowPlanted,
        List<ForeshadowPaid> foreshadowPaid) {

    public static AnalysisFacts empty() {
        return new AnalysisFacts(null, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
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

    /**
     * 角色在**本章结束时**的状态变化。
     *
     * <p>只输出真正发生变化的角色；连续若干章没变化的角色不要重复输出，
     * 否则时间线表会被同一条状态灌满。</p>
     */
    public record CharacterState(String name,
                                 String location,
                                 String condition,
                                 String mood,
                                 List<String> knowsAdded,
                                 List<String> knowsHidden) {
    }

    /**
     * 本章新埋的伏笔。
     *
     * <p>{@code plannedPayoffChapter} 允许为空——作者自己也不知道什么时候收，
     * 空着由「埋设章 + 默认周期」判超期即可。</p>
     */
    public record ForeshadowPlanted(String title,
                                    String content,
                                    Integer plannedPayoffChapter) {
    }

    /** 本章回收的伏笔：{@code title} 必须与「已有伏笔」逐字一致 */
    public record ForeshadowPaid(String title, String evidence) {
    }
}
