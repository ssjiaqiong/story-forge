package com.storyforge.modules.bookshelf.service;

import com.storyforge.infra.ai.PromptKit;
import com.storyforge.infra.text.AiFlavorMeter;
import com.storyforge.modules.bookshelf.dto.ChapterEventVO;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.EventParticipantVO;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.HashMap;
import java.util.Map;

/**
 * 章节分析的提示词。
 *
 * <p>拆成两次调用，各管一件事：</p>
 * <ol>
 *   <li>事实同步：只登记本章真正发生的变化（新人物、人物 / 物品 / 势力状态、事件是否结束）；</li>
 *   <li>质量评审：字数、大纲贴合度、AI 味，各自给分并给依据。</li>
 * </ol>
 *
 * <p>事实同步用「已有清单」约束名字，避免把旧人物当新人、把提及当出场；评审把本地统计
 * 一起塞进提示词，要求模型不得与统计矛盾。</p>
 */
@Component
public class ChapterAnalysisPromptBuilder {

    private static final int MAX_SUMMARY_CHARS = 1200;

    private final com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService;

    public ChapterAnalysisPromptBuilder(
            com.storyforge.modules.ai.service.PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /** 事实抽取的 system：优先用用户模板（含锁定输出格式），未接入时回退内置 */
    public String factSystem(String novelTitle, Integer chapterNo) {
        String rendered = promptTemplateService.system(com.storyforge.modules.ai.scene.AiScene.SYNC_EXTRACT,
                java.util.Map.of(
                        "novelTitle", novelTitle == null || novelTitle.isBlank() ? "这本书" : novelTitle,
                        "chapterNo", chapterNo == null ? "-" : String.valueOf(chapterNo)));
        return rendered.isBlank() ? factSystem() : rendered;
    }

    /** 评分的 system：优先用用户模板（含锁定输出格式），未接入时回退内置 */
    public String scoreSystem(String novelTitle, Integer chapterNo, int targetWords) {
        String rendered = promptTemplateService.system(com.storyforge.modules.ai.scene.AiScene.ANALYSIS_GEN,
                java.util.Map.of(
                        "novelTitle", novelTitle == null || novelTitle.isBlank() ? "这本书" : novelTitle,
                        "chapterNo", chapterNo == null ? "-" : String.valueOf(chapterNo),
                        "targetWords", String.valueOf(targetWords)));
        return rendered.isBlank() ? scoreSystem() : rendered;
    }

    public String factSystem() {
        return """
                你是网络小说的责任编辑，作者刚写完最新一章，你负责做「设定同步」。
                你只做事实登记，不做文学评价：正文没写的一律不记，宁可漏记也不要推测。
                """ + PromptKit.JSON_CONTRACT + """
                【判定口径】
                - 只有正文里实际发生的变化才记：回忆、梦境、假设、他人转述、计划中的事都不算。
                - 人物姓名必须与「已有角色」逐字一致；只有真正首次出场的陌生人才放进 newCharacters。
                - 只被提到名字、没有台词也没有动作的人，不算出场，不算新人物。
                - 一个字段一条记录：同一角色死了又换了身份，就写两条。
                """;
    }

    public String factPrompt(ChapterEntity chapter,
                             String novelTitle,
                             String genre,
                             ChapterVO detail,
                             List<CharacterEntity> characters,
                             List<ItemEntity> items,
                             List<FactionEntity> factions,
                             Map<Long, String> factionNames,
                             List<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity> attributes,
                             List<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity> inventories) {
        StringBuilder builder = new StringBuilder();
        builder.append("【作品】").append(nullToEmpty(novelTitle));
        if (hasText(genre)) {
            builder.append("（").append(genre).append("）");
        }
        builder.append("\n【本章】第 ").append(chapter.getChapterNo()).append(" 章 ").append(nullToEmpty(chapter.getTitle())).append('\n');
        builder.append("【本章大纲】").append(clip(chapter.getSummary(), MAX_SUMMARY_CHARS)).append('\n');

        List<EventParticipantVO> participants = detail == null ? List.of() : detail.participants();
        if (participants != null && !participants.isEmpty()) {
            builder.append("【本章出场人物】")
                    .append(String.join("、", participants.stream().map(EventParticipantVO::name).toList()))
                    .append('\n');
        }
        List<ChapterEventVO> events = detail == null ? List.of() : detail.events();
        if (events != null && !events.isEmpty()) {
            builder.append("【本章关联事件】\n");
            for (ChapterEventVO event : events) {
                builder.append("- ").append(event.title())
                        .append("（当前状态：").append(event.statusLabel()).append("）\n");
            }
        }

        builder.append("【已有角色】姓名｜身份｜定位｜生死｜所属势力\n");
        if (characters.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            for (CharacterEntity character : characters) {
                builder.append("- ").append(character.getName())
                        .append('｜').append(nullToEmpty(character.getIdentity()))
                        .append('｜').append(NovelLabels.label(NovelLabels.ROLE, character.getRolePos()))
                        .append('｜').append(NovelLabels.label(NovelLabels.LIFE, character.getLifeStatus()))
                        .append('｜').append(factionNames.getOrDefault(character.getFactionId(), "无"))
                        .append('\n');
            }
        }

        builder.append("【已有物品】名称｜持有者｜状态｜数量\n");
        if (items.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            for (ItemEntity item : items) {
                builder.append("- ").append(item.getName())
                        .append('｜').append(nullToEmpty(item.getOwner()))
                        .append('｜').append(NovelLabels.label(NovelLabels.ITEM_STATUS, item.getStatus()))
                        .append('｜').append(item.getQuantity() == null ? "不计" : item.getQuantity())
                        .append('\n');
            }
        }

        builder.append("【已有势力】名称｜状态｜范围\n");
        if (factions.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            for (FactionEntity faction : factions) {
                builder.append("- ").append(faction.getName())
                        .append('｜').append(NovelLabels.label(NovelLabels.FACTION_STATUS, faction.getStatus()))
                        .append('｜').append(nullToEmpty(faction.getScope()))
                        .append('\n');
            }
        }

        builder.append("【已有属性】角色｜属性名｜当前值\n");
        if (attributes == null || attributes.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            Map<Long, String> characterNames = new HashMap<>();
            for (CharacterEntity character : characters) {
                characterNames.put(character.getId(), character.getName());
            }
            for (com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity attribute : attributes) {
                builder.append("- ").append(characterNames.getOrDefault(attribute.getCharacterId(), "未知"))
                        .append('｜').append(nullToEmpty(attribute.getName()))
                        .append('｜').append(nullToEmpty(attribute.getValue()))
                        .append(nullToEmpty(attribute.getUnit()))
                        .append('\n');
            }
        }

        builder.append("【已有背包】角色｜条目｜数量｜状态\n");
        if (inventories == null || inventories.isEmpty()) {
            builder.append("（暂无）\n");
        } else {
            Map<Long, String> characterNames = new HashMap<>();
            for (CharacterEntity character : characters) {
                characterNames.put(character.getId(), character.getName());
            }
            for (com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity entry : inventories) {
                builder.append("- ").append(characterNames.getOrDefault(entry.getCharacterId(), "未知"))
                        .append('｜').append(nullToEmpty(entry.getName()))
                        .append('｜').append(entry.getQuantity() == null ? "不定"
                                : entry.getQuantity().stripTrailingZeros().toPlainString())
                        .append(nullToEmpty(entry.getUnit()))
                        .append('｜').append(NovelLabels.label(NovelLabels.INVENTORY_STATUS, entry.getStatus()))
                        .append('\n');
            }
        }

        builder.append("\n【本章正文】\n").append(nullToEmpty(chapter.getContent())).append('\n');

        builder.append("""

                【任务】读完本章，输出四类登记信息。
                1. newCharacters：本章首次出场、且不在「已有角色」里的有名有姓的人物，最多 8 个。
                   只写正文能支撑的信息，拿不准的字段留空字符串；身份与动机各不超过 30 字。
                2. characterChanges：已有角色的变化。field 只能取：
                   lifeStatus（取值 ALIVE / DEAD / MISSING / UNKNOWN）、
                   identity（新的身份职位，写名词短语）、
                   faction（新的所属势力名，必须能在「已有势力」里找到；脱离势力写「无」）、
                   rolePos（取值 PROTAGONIST / SUPPORTING / ANTAGONIST / MINOR，仅当戏份定位明显改变时才记）。
                3. itemChanges：已有物品的变化。field 只能取：
                   status（取值 ACTIVE / DESTROYED / ABANDONED / LOST / CONSUMED）、
                   owner（新的持有者姓名）、quantity（整数形式的数量）。
                4. factionChanges：已有势力的变化。field 只能取：
                   status（取值 ACTIVE / DORMANT / DESTROYED / DISBANDED）、scope（新的管辖或营业范围）。
                5. eventChanges：只从「本章关联事件」里选，标题逐字一致；事件在本章收尾就记 DONE，
                   明确被放弃记 ABANDONED，推进但没结束记 ONGOING。没有关联事件的章节返回空数组。
                6. attributeChanges：已有角色的属性变化。name 是角色姓名（必须与「已有角色」逐字一致），
                   field 是属性名（如 存款 / 积分 / 体质 / 等级），after 是本章结束后的新值（纯数字或短语）。
                   正文没写具体数值就不要猜；属性名不在「已有属性」里且正文明确给出，也可以记为新增属性。
                7. inventoryChanges：已有角色背包的变化。name 是角色姓名，field 是条目名
                   （必须与「已有背包」里的名称逐字一致，或正文明确出现的新物资），after 是本章结束后的数量，
                   只写数字（如 20），单位为「元 / 枚 / 两」时不要带进 after。
                   如果本章明确写这样东西丢了或彻底用光了，after 改写成 LOST（遗失）或 CONSUMED（已消耗），
                   此时不要再报一次数量；东西还在身上只是数量变了，就还是只写数字。
                每条记录都要有 reason（不超过 40 字）与 evidence（正文原句，不超过 60 字）。

                【输出 JSON】
                {
                  "newCharacters": [
                    {"name": "沈九", "identity": "渡口船工", "rolePos": "MINOR", "gender": "男",
                     "ageDesc": "四十上下", "appearance": "左手缺一指，常穿油布褂",
                     "personality": "话少，认钱不认人", "motivation": "攒钱赎回被押的船",
                     "reason": "第 3 段与主角在渡口交易，首次出场"}
                  ],
                  "characterChanges": [
                    {"name": "陈砚", "field": "lifeStatus", "after": "DEAD",
                     "reason": "坠崖后尸体被水冲走", "evidence": "他脚下一空，人就没入黑水里"}
                  ],
                  "itemChanges": [
                    {"name": "半枚铜钱", "field": "owner", "after": "沈氏",
                     "reason": "坠崖前把铜钱塞给她", "evidence": "他把铜钱按进她掌心"}
                  ],
                  "factionChanges": [
                    {"name": "守夜人", "field": "status", "after": "DESTROYED",
                     "reason": "据点被烧，头目伏法", "evidence": "守夜人的牌子被扔进火里"}
                  ],
                  "eventChanges": [
                    {"title": "断桥之约", "status": "DONE",
                     "reason": "约定在本章兑现", "evidence": "两人在断桥见了最后一面"}
                  ],
                  "attributeChanges": [
                    {"name": "陈砚", "field": "存款", "after": "250",
                     "reason": "付了渡船钱", "evidence": "他数出五十枚铜钱递过去"}
                  ],
                  "inventoryChanges": [
                    {"name": "陈砚", "field": "铜钱", "after": "30",
                     "reason": "渡船花掉二十枚", "evidence": "袋子里只剩薄薄一层"}
                  ]
                }
                没有内容的类别输出空数组 []，不要省略字段。
                """);
        return builder.toString();
    }

    public String scoreSystem() {
        return """
                你是小说平台的质检编辑，负责审最新章节的三件事：是否写到了大纲要求的地方、文风是否像 AI 批量生成、字数是否合适。
                你只输出 JSON，不写客套话。
                """ + PromptKit.JSON_CONTRACT + """
                【评分纪律】
                - 先找证据再给分：每条结论都要能落到正文的具体内容上。
                - 大纲贴合看「该发生的事有没有发生、该出场的人有没有做事」，不看辞藻是否漂亮；
                  超出大纲但有增色的内容算加分项，写进 hits。
                - AI 率分数含义：0 = 一眼就是 AI，50 = 人机混杂，100 = 完全像人写。
                  评分依据：套话与模板化描写、句式和段落的均匀程度、情绪是否直给、细节是否具体可感、
                  对话是否像真人说话、是否有作者独有的偏执与毛边。
                - 给定的【本地统计】是硬证据，你的评分不得与它明显矛盾：套话密度高、句首重复多，就不能给高分。
                - 结论要具体：不要写「可以更生动」这类空话，要指出改哪一段、怎么改。
                """;
    }

    public String scorePrompt(ChapterEntity chapter,
                              String novelTitle,
                              String genre,
                              ChapterVO detail,
                              ChapterVO previous,
                              AiFlavorMeter.Metrics metrics,
                              int targetWords,
                              int minWords,
                              int maxWords) {
        StringBuilder builder = new StringBuilder();
        builder.append("【作品】").append(nullToEmpty(novelTitle));
        if (hasText(genre)) {
            builder.append("（").append(genre).append("）");
        }
        builder.append('\n');
        builder.append("【本章】第 ").append(chapter.getChapterNo()).append(" 章 ").append(nullToEmpty(chapter.getTitle())).append('\n');
        builder.append("【本章大纲】").append(hasText(chapter.getSummary()) ? chapter.getSummary() : "（作者没有写本章大纲）").append('\n');

        List<EventParticipantVO> participants = detail == null ? List.of() : detail.participants();
        if (participants != null && !participants.isEmpty()) {
            builder.append("【本章应出场人物】")
                    .append(String.join("、", participants.stream().map(EventParticipantVO::name).toList()))
                    .append('\n');
        }
        List<ChapterEventVO> events = detail == null ? List.of() : detail.events();
        if (events != null && !events.isEmpty()) {
            builder.append("【本章应写事件】\n");
            for (ChapterEventVO event : events) {
                builder.append("- ").append(event.title()).append("（").append(event.statusLabel()).append("）\n");
            }
        }
        if (previous != null) {
            builder.append("【上一章】第 ").append(previous.chapterNo()).append(" 章 ").append(previous.title()).append('\n');
            builder.append("【上一章大纲】").append(clip(previous.summary(), 500)).append('\n');
        }

        builder.append("【字数】本章汉字 ").append(metrics.hanCount())
                .append("，目标 ").append(targetWords)
                .append("，合理区间 ").append(minWords).append('~').append(maxWords).append('\n');
        builder.append("【本地统计】\n").append(new AiFlavorMeter().describe(metrics)).append('\n');
        builder.append("【本章正文】\n").append(nullToEmpty(chapter.getContent())).append('\n');

        builder.append("""

                【任务】输出三项判定。
                1. outline：与本章大纲、应写事件的贴合度，0~100 整数。
                   90~100 全部落实且有增色；75~89 主要节点落实、细节有偏差；60~74 骨架对但关键节点缺失或偏移；
                   40~59 只沾到边；40 以下基本无关。作者没写大纲时，按「章节标题 + 上一章收尾」判断是否承接自然，
                   并在 verdict 里注明「无大纲，按承接判断」。
                   hits 写 2~6 条做到的地方；misses 写 0~4 条没做到或写歪的地方；suggestions 写 2~4 条可执行的修改建议。
                2. aiFlavor：AI 率 0~100 整数，0 为纯 AI 味。
                   verdict 用一句话定性（如「套话密集，句式整齐得像模板」）；
                   signals 给 3~5 条判断依据（name 为短标签，detail 说明在正文里看到什么）；
                   examples 摘 2~4 句最能说明问题的原句，每句不超过 40 字。
                3. lengthComment：一句话点评字数是否合适，说明多了或少了会伤到哪里。

                【输出 JSON】
                {
                  "outline": {"score": 82, "verdict": "主线节点齐，结尾收得急",
                    "hits": ["沈氏赴约，兑现了断桥之约", "铜钱交到沈氏手里，线索完成转移"],
                    "misses": ["大纲要求的药铺查封没有写"],
                    "suggestions": ["在两人分手前补一段查封动静，让主角的处境更紧"]},
                  "aiFlavor": {"score": 46, "verdict": "描写工整但套话偏多，人物说话像一个腔调",
                    "signals": [{"name": "套话密度", "detail": "两段里出现三次「眼中闪过一丝」"}],
                    "examples": ["他的眼中闪过一丝复杂的神色"]},
                  "lengthComment": "比目标少两成，结尾的转折显得仓促"
                }
                """);
        return builder.toString();
    }

    private String clip(String text, int max) {
        if (!hasText(text)) {
            return "（空）";
        }
        String trimmed = text.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max) + "……";
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
