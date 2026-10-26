package com.storyforge.modules.bookshelf.dto;

import com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity;
import com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity;
import com.storyforge.modules.bookshelf.entity.ChapterCharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity;
import com.storyforge.modules.bookshelf.entity.CharacterRelationEntity;
import com.storyforge.modules.bookshelf.entity.EventParticipantEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.MasterOutlineEntity;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import com.storyforge.modules.novel.agent.entity.NovelAgentMessageEntity;
import com.storyforge.modules.novel.agent.entity.NovelAgentSessionEntity;
import com.storyforge.modules.novel.entity.NovelEntity;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 小说全量导出包（导入的入参也是它）。
 *
 * <p>覆盖一部小说当前的全部内容：基本信息、设定文档、总纲、人物（含属性/背包/关系）、
 * 势力、物品、事件、章节（含大纲与正文）、章节关联、分析记录、修改履历、AI 助手会话、
 * 封面（图片 Base64 内嵌）。</p>
 *
 * <p>不含站点级数据：{@code t_ai_usage}（用量计费）、{@code t_book_digest}（拆书记录）、
 * 灵感会话（{@code inspiration_session_id} 导入时置空）。</p>
 *
 * <p>所有行都带原雪花 id，导入时按「旧 id → 新 id」重映射外键，因此可以在任意站点间搬运。</p>
 */
@Data
public class NovelExportPackage {

    public static final String FORMAT = "storyforge-novel-export";
    public static final int FORMAT_VERSION = 1;

    /** 固定标识，导入时校验 */
    private String format = FORMAT;

    /** 格式版本，后续结构变更时递增 */
    private Integer formatVersion = FORMAT_VERSION;

    /** 导出时间 yyyy-MM-dd HH:mm:ss */
    private String exportedAt;

    /** 导出方标识 */
    private String app = "StoryForge";

    private NovelEntity novel;

    private List<SettingDocEntity> settingDocs = new ArrayList<>();

    private List<MasterOutlineEntity> masterOutlines = new ArrayList<>();

    private List<FactionEntity> factions = new ArrayList<>();

    private List<FactionMemberEntity> factionMembers = new ArrayList<>();

    private List<CharacterEntity> characters = new ArrayList<>();

    private List<CharacterRelationEntity> characterRelations = new ArrayList<>();

    private List<CharacterAttributeEntity> characterAttributes = new ArrayList<>();

    private List<CharacterInventoryEntity> characterInventories = new ArrayList<>();

    private List<ItemEntity> items = new ArrayList<>();

    private List<TimelineEventEntity> timelineEvents = new ArrayList<>();

    private List<EventParticipantEntity> eventParticipants = new ArrayList<>();

    private List<ChapterEntity> chapters = new ArrayList<>();

    private List<ChapterCharacterEntity> chapterCharacters = new ArrayList<>();

    private List<ChapterEventEntity> chapterEvents = new ArrayList<>();

    private List<ChapterAnalysisEntity> chapterAnalyses = new ArrayList<>();

    private List<ChangeHistoryEntity> changeHistories = new ArrayList<>();

    private List<NovelAgentSessionEntity> agentSessions = new ArrayList<>();

    private List<NovelAgentMessageEntity> agentMessages = new ArrayList<>();

    private List<NovelCoverExport> covers = new ArrayList<>();
}
