package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 拆书结果：主线脉络 + 改编后的完整设定。
 *
 * <p>只到「大纲」为止，<b>不生成正文</b>：章节只写标题与摘要，正文留给用户自己在工作台写。</p>
 */
@Data
@Schema(description = "拆书结果")
public class DeconstructResult {

    @Schema(description = "新书名")
    private String title;

    @Schema(description = "题材")
    private String genre;

    @Schema(description = "标签")
    private List<String> tags = new ArrayList<>();

    @Schema(description = "简介")
    private String synopsis;

    @Schema(description = "世界观")
    private String worldview;

    @Schema(description = "背景设定")
    private String background;

    @Schema(description = "核心冲突")
    private String coreConflict;

    @Schema(description = "核心看点")
    private List<String> highlights = new ArrayList<>();

    @Schema(description = "文风与叙事基调")
    private String style;

    @Schema(description = "主线脉络（原文分析结果）")
    private Spine spine;

    @Schema(description = "主角")
    private CharacterDraft protagonist;

    @Schema(description = "配角与反派")
    private List<CharacterDraft> characters = new ArrayList<>();

    @Schema(description = "人物关系")
    private List<Relation> relations = new ArrayList<>();

    @Schema(description = "势力")
    private List<Faction> factions = new ArrayList<>();

    @Schema(description = "章节大纲（不含正文）")
    private List<Chapter> chapters = new ArrayList<>();

    @Schema(description = "事件池")
    private List<Event> events = new ArrayList<>();

    @Schema(description = "改编程度：LIGHT / MEDIUM / HEAVY / REBUILD")
    private String adaptationLevel;

    @Schema(description = "改编程度中文名")
    private String adaptationLabel;

    @Schema(description = "背景改编方向")
    private String backgroundDirection;

    @Schema(description = "原文汉字数")
    private Integer sourceChars;

    @Schema(description = "原文识别章节数")
    private Integer sourceChapters;

    @Schema(description = "是否对原文做了抽样通读")
    private Boolean sampled;

    @Schema(description = "使用的模型")
    private String model;

    @Schema(description = "总耗时（毫秒）")
    private Long latencyMs;

    /**
     * 主线脉络。
     */
    @Data
    @Schema(description = "主线脉络")
    public static class Spine {

        @Schema(description = "一句话故事内核（不含原文专有名词）")
        private String logline;

        @Schema(description = "作品真正在讨论的问题")
        private String theme;

        @Schema(description = "主线走向：开局处境 → 升级 → 中点反转 → 最低谷 → 终局选择")
        private String mainline;

        @Schema(description = "分幕结构")
        private List<Act> acts = new ArrayList<>();

        @Schema(description = "关键转折（原本以为 → 实际发生 → 后果）")
        private List<String> turns = new ArrayList<>();

        @Schema(description = "人物功能表")
        private List<Function> characterFunctions = new ArrayList<>();

        @Schema(description = "结局形态与它回答的问题")
        private String ending;
    }

    /**
     * 分幕。
     */
    @Data
    @Schema(description = "分幕结构")
    public static class Act {

        @Schema(description = "幕名，如 第一幕")
        private String name;

        @Schema(description = "覆盖范围，如 前四分之一")
        private String range;

        @Schema(description = "这一幕发生了什么、主角处境如何变化")
        private String content;
    }

    /**
     * 人物功能。
     */
    @Data
    @Schema(description = "人物功能")
    public static class Function {

        @Schema(description = "在故事里的功能：主角 / 对手 / 引路人 / 镜像 / 代价承担者")
        private String function;

        @Schema(description = "原文中的角色（只在分析阶段出现，不进入新书）")
        private String originalRole;

        @Schema(description = "该角色对主线的推动作用")
        private String role;
    }

    /**
     * 人物关系。
     */
    @Data
    @Schema(description = "人物关系")
    public static class Relation {

        @Schema(description = "关系一端（新书人物姓名）")
        private String from;

        @Schema(description = "关系另一端（新书人物姓名）")
        private String to;

        @Schema(description = "关系类型编码：MASTER_APPRENTICE/FAMILY/SPOUSE/LOVER/FRIEND/ALLY/ENEMY/RIVAL/COLLEAGUE/OTHER")
        private String type;

        @Schema(description = "关系说明")
        private String description;
    }

    /**
     * 势力。
     */
    @Data
    @Schema(description = "势力")
    public static class Faction {

        @Schema(description = "势力名称")
        private String name;

        @Schema(description = "类型：公司 / 帮派 / 国家 / 组织 / 宗门 / 其他")
        private String category;

        @Schema(description = "状态：ACTIVE / DORMANT / DESTROYED / DISBANDED")
        private String status;

        @Schema(description = "营业或管辖范围")
        private String scope;

        @Schema(description = "备注")
        private String remark;

        @Schema(description = "成员")
        private List<Member> members = new ArrayList<>();
    }

    /**
     * 势力成员。
     */
    @Data
    @Schema(description = "势力成员")
    public static class Member {

        @Schema(description = "人物姓名（新书人物）")
        private String character;

        @Schema(description = "在该势力中的职位")
        private String position;
    }

    /**
     * 章节大纲。
     */
    @Data
    @Schema(description = "章节大纲")
    public static class Chapter {

        @Schema(description = "卷号")
        private Integer volumeNo;

        @Schema(description = "卷名")
        private String volumeTitle;

        @Schema(description = "章节号")
        private Integer chapterNo;

        @Schema(description = "章节标题")
        private String title;

        @Schema(description = "章节摘要（大纲）")
        private String summary;

        @Schema(description = "本章出场人物（姓名）")
        private List<String> characters = new ArrayList<>();

        @Schema(description = "本章关联事件（事件标题）")
        private List<String> events = new ArrayList<>();
    }

    /**
     * 事件。
     */
    @Data
    @Schema(description = "事件")
    public static class Event {

        @Schema(description = "事件标题")
        private String title;

        @Schema(description = "故事内时间")
        private String eventTime;

        @Schema(description = "事件描述")
        private String description;

        @Schema(description = "状态：UNUSED / ONGOING / DONE / ABANDONED")
        private String status;

        @Schema(description = "参与角色（姓名）")
        private List<String> participants = new ArrayList<>();
    }
}
