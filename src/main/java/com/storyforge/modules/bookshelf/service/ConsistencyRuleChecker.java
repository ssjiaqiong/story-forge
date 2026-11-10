package com.storyforge.modules.bookshelf.service;

import com.storyforge.modules.bookshelf.dto.ConsistencyDraft;
import com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity;
import com.storyforge.modules.bookshelf.entity.ChapterCharacterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.ForeshadowEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一致性检查的<b>规则层</b>：纯查询与比对，不调 AI、不花 token，秒级返回。
 *
 * <p>八条规则的共同约束是<b>必须能给出可核对的证据</b>：章节号、状态值、数量、引用片段。
 * 拿不出证据的判断一律不出——误报一次的代价远高于漏报一次，作者会直接不看这个页面。</p>
 *
 * <p>关于「死人出场」的判定精度：{@code t_character.life_status} 只有当前值，没有「第几章死的」。
 * 这里用 {@code t_change_history} 里 {@code field_key='lifeStatus'} 且 {@code after_state} 为死亡 / 失踪的
 * 那条记录的章号当作死亡章；<b>查不到时降级为 P2 并注明「未记录死亡章节」</b>——
 * 没有依据就报 P0，等于拿猜测去指控作者的稿子。</p>
 */
@Component
public class ConsistencyRuleChecker {

    /** 与 {@code NovelAssetServiceImpl} 保持一致：sort_no = volume_no * 1000 + chapter_no */
    private static final int CHAPTER_SORT_UNIT = 1000;

    private static final int MAX_DETAIL = 800;
    private static final int MAX_EVIDENCE = 400;
    private static final int MAX_TITLE = 120;
    /** 伏笔计划未定回收章时的默认周期：埋设章之后这么多章还没收就算超期 */
    private static final int DEFAULT_PAYOFF_CHAPTERS = 30;

    /**
     * 跑一遍全部规则。
     *
     * @param chapters 本书全部章节（含 sort_no，由调用方按排序列好）
     * @param characters 全部在世与已故人物
     * @param items 全部物品
     * @param events 全部时间线事件
     * @param chapterCharacters 章节 ↔ 人物关联（含指向已删人物的悬空行）
     * @param chapterEvents 章节 ↔ 事件关联（同上）
     * @param lifeChanges 生死状态变更履历，用来反查「第几章死的」
     * @param analyzedChapterIds 已有分析记录的章节 id
     */
    public List<ConsistencyDraft> check(List<ChapterEntity> chapters,
                                        List<CharacterEntity> characters,
                                        List<ItemEntity> items,
                                        List<TimelineEventEntity> events,
                                        List<ChapterCharacterEntity> chapterCharacters,
                                        List<ChapterEventEntity> chapterEvents,
                                        List<ChangeHistoryEntity> lifeChanges,
                                        Set<Long> analyzedChapterIds,
                                        List<ForeshadowEntity> foreshadows) {
        List<ConsistencyDraft> found = new ArrayList<>();
        Map<Long, ChapterEntity> chapterById = new HashMap<>();
        for (ChapterEntity chapter : chapters) {
            chapterById.put(chapter.getId(), chapter);
        }
        Map<Long, CharacterEntity> characterById = new HashMap<>();
        for (CharacterEntity character : characters) {
            characterById.put(character.getId(), character);
        }
        Map<Long, TimelineEventEntity> eventById = new HashMap<>();
        for (TimelineEventEntity event : events) {
            eventById.put(event.getId(), event);
        }

        /** 人物 -> 死亡/失踪所在章号；查不到就没有这个键 */
        Map<Long, Integer> goneAtChapter = new HashMap<>();
        Map<Long, String> goneReason = new HashMap<>();
        for (ChangeHistoryEntity row : lifeChanges) {
            if (row.getEntityId() == null || row.getChapterNo() == null) {
                continue;
            }
            String after = row.getAfterState();
            if (!"DEAD".equalsIgnoreCase(after) && !"MISSING".equalsIgnoreCase(after)) {
                continue;
            }
            Integer existing = goneAtChapter.get(row.getEntityId());
            // 同一人物可能有多条（死了又失踪），取最早的那一章，判定才保守
            if (existing == null || row.getChapterNo() < existing) {
                goneAtChapter.put(row.getEntityId(), row.getChapterNo());
                goneReason.put(row.getEntityId(), NovelLabels.label(NovelLabels.LIFE, after));
            }
        }

        checkCharacterState(found, chapterCharacters, characterById, chapterById, goneAtChapter, goneReason);
        checkItemState(found, items);
        checkEarlyAppearance(found, chapterCharacters, characterById, chapterById);
        checkEventStatus(found, chapterEvents, eventById, chapterById);
        checkStructure(found, chapters);
        checkOutlineGap(found, chapters);
        checkArchiveGap(found, chapters, analyzedChapterIds);
        checkDangling(found, chapterCharacters, chapterEvents, characterById, eventById, chapterById);
        checkForeshadowOverdue(found, foreshadows, chapters);
        return found;
    }

    /* ------------------------------------------------------------------ */
    /* 九条规则                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * R9 伏笔超期：第 5 章埋的线，说是第 15 章收，写到第 20 章还没动静。
     *
     * <p>这是「读者觉得烂尾」的第一大来源，而它以前根本查不出来——伏笔只写在提示词要求里，
     * 没有台账，埋了多少、收了哪些全靠记忆。现在有台账，就能算超期。</p>
     *
     * <p>只报 {@code P2}：超期不等于写错，作者可能是故意拖到后面一起收，
     * 所以只提示、不阻断自动写书。</p>
     *
     * <p><b>进度口径</b>：这里的「写到第几章」只认已经有正文的章节，
     * 见 {@link #latestWrittenChapterNo}。</p>
     */
    private void checkForeshadowOverdue(List<ConsistencyDraft> found, List<ForeshadowEntity> foreshadows,
                                        List<ChapterEntity> chapters) {
        if (foreshadows == null || foreshadows.isEmpty()) {
            return;
        }
        int latest = latestWrittenChapterNo(chapters);
        if (latest <= 0) {
            return;
        }
        for (ForeshadowEntity item : foreshadows) {
            if (!ForeshadowEntity.STATUS_OPEN.equals(item.getStatus())) {
                continue;
            }
            Integer planted = item.getPlantedChapterNo();
            if (planted == null || planted >= latest) {
                continue;
            }
            Integer planned = item.getPlannedPayoffChapter();
            int dueAt = planned != null && planned > 0 ? planned : planted + DEFAULT_PAYOFF_CHAPTERS;
            if (latest <= dueAt) {
                continue;
            }
            int overdue = latest - dueAt;
            String title = item.getTitle() == null ? "（未命名伏笔）" : item.getTitle();
            found.add(new ConsistencyDraft("RULE", item.getPlantedChapterId(), planted, null,
                    "FORESHADOW", "P2",
                    clip("伏笔「" + title + "」已超期 " + overdue + " 章未回收", MAX_TITLE),
                    clip("第 " + planted + " 章埋下，计划第 " + dueAt + " 章回收；目前写到第 " + latest
                            + " 章，已经超了 " + overdue + " 章。读者会记得这条线，一直不收会被当成烂尾。", MAX_DETAIL),
                    clip("第 " + planted + " 章埋设，计划第 " + dueAt + " 章回收，当前最新第 " + latest + " 章", MAX_EVIDENCE),
                    "在近期章节里给它一个交代，或到「伏笔」页把它标记为已回收 / 放弃。"));
        }
    }

    /**
     * 全书「写到第几章」的统一口径：<b>只认已经写出正文的章节</b>。
     *
     * <p>{@code t_chapter} 里同时躺着两种章：已写正文的，和「只排了大纲、正文还是空」的草稿章
     * （自动写书是先排完整本大纲再逐章写正文，所以书的章号可能一开局就是 50）。
     * 如果按章节表的最大 chapter_no 算进度，刚写到第 10 章的书会被当成写到第 50 章，
     * 伏笔会在第 31 章「准时」集体误报超期——用户看到的正是「只写了一章，却提示超期 19 章」。</p>
     *
     * <p>{@code ChapterFactService#latestChapterNo} 用的是同一口径，改一处必须改两处。</p>
     */
    private static int latestWrittenChapterNo(List<ChapterEntity> chapters) {
        int latest = 0;
        for (ChapterEntity chapter : chapters) {
            String content = chapter.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (chapter.getChapterNo() != null && chapter.getChapterNo() > latest) {
                latest = chapter.getChapterNo();
            }
        }
        return latest;
    }

    /** R1 已故 / 失踪人物在之后出场。 */
    private void checkCharacterState(List<ConsistencyDraft> found,
                                     List<ChapterCharacterEntity> links,
                                     Map<Long, CharacterEntity> characterById,
                                     Map<Long, ChapterEntity> chapterById,
                                     Map<Long, Integer> goneAtChapter,
                                     Map<Long, String> goneReason) {
        for (ChapterCharacterEntity link : links) {
            CharacterEntity character = characterById.get(link.getCharacterId());
            ChapterEntity chapter = chapterById.get(link.getChapterId());
            if (character == null || chapter == null) {
                continue;
            }
            String life = character.getLifeStatus();
            if (!"DEAD".equalsIgnoreCase(life) && !"MISSING".equalsIgnoreCase(life)) {
                continue;
            }
            String state = NovelLabels.label(NovelLabels.LIFE, life);
            Integer goneAt = goneAtChapter.get(character.getId());
            if (goneAt == null) {
                // 履历里没有死亡章号，只能说「全书范围内这是矛盾的」，不能硬指某章
                found.add(new ConsistencyDraft("RULE", chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                        "CHARACTER_STATE", "P2",
                        "人物「" + character.getName() + "」已" + state + "却仍登记出场",
                        "人物档案里「" + character.getName() + "」的生命状态是" + state
                                + "，但它出现在第 " + chapter.getChapterNo() + " 章的出场人物里。"
                                + "变更履历里没有记录" + state + "发生在哪一章，无法判断是否属于回忆或闪回，请人工确认。",
                        "人物档案：生命状态=" + state + "；第 " + chapter.getChapterNo() + " 章出场人物含此角色",
                        "若是回忆或闪回，在出场备注里写明；若是笔误，把该章的出场人物改掉。"));
                continue;
            }
            Integer chapterNo = chapter.getChapterNo();
            if (chapterNo == null || chapterNo <= goneAt) {
                continue;
            }
            String from = goneReason.getOrDefault(character.getId(), state);
            found.add(new ConsistencyDraft("RULE", chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                    "CHARACTER_STATE", "P0",
                    "人物「" + character.getName() + "」在第 " + goneAt + " 章已" + from + "，第 " + chapterNo + " 章又出场",
                    "第 " + goneAt + " 章记录「" + character.getName() + "」变为" + from
                            + "，但第 " + chapterNo + " 章「" + chapter.getTitle() + "」的出场人物里仍有他。"
                            + "除非这一章明确写了回忆、闪回、托梦或尸体登场，否则就是硬伤。",
                    "第 " + goneAt + " 章变更履历：" + character.getName() + " → " + from
                            + "；第 " + chapterNo + " 章出场人物：" + character.getName(),
                    "把这一章的出场人物换成别人，或明确写成回忆 / 闪回并在出场备注里注明。"));
        }
    }

    /** R2 已摧毁 / 已遗失 / 已消耗的物品仍有数量或仍挂在某人名下。 */
    private void checkItemState(List<ConsistencyDraft> found, List<ItemEntity> items) {
        for (ItemEntity item : items) {
            String status = item.getStatus();
            if (!"DESTROYED".equalsIgnoreCase(status)
                    && !"LOST".equalsIgnoreCase(status)
                    && !"CONSUMED".equalsIgnoreCase(status)) {
                continue;
            }
            String label = NovelLabels.label(NovelLabels.ITEM_STATUS, status);
            Integer quantity = item.getQuantity();
            boolean hasQuantity = quantity != null && quantity > 0;
            boolean hasOwner = item.getOwner() != null && !item.getOwner().isBlank();
            if (!hasQuantity && !hasOwner) {
                continue;
            }
            String severity = hasQuantity ? "P0" : "P1";
            StringBuilder evidence = new StringBuilder("物品档案：状态=").append(label);
            if (hasQuantity) {
                evidence.append("｜数量=").append(quantity);
            }
            if (hasOwner) {
                evidence.append("｜持有者=").append(item.getOwner());
            }
            found.add(new ConsistencyDraft("RULE", null, null, null, "ITEM_STATE", severity,
                    "物品「" + item.getName() + "」已" + label + (hasQuantity ? "却仍有 " + quantity + " 件" : "却仍登记在「" + item.getOwner() + "」名下"),
                    "物品档案里「" + item.getName() + "」的状态是" + label
                            + (hasQuantity ? "，却还记着 " + quantity + " 件。" : "，却仍登记在「" + item.getOwner() + "」名下。")
                            + "这类条目通常是分析时漏改状态留下的，正文里一旦再拿出来用就会穿帮。",
                    evidence.toString(),
                    hasQuantity ? "把数量改成 0，或把状态改回持有中（若正文里它还在）。" : "清空持有者，或把状态改回持有中。"));
        }
    }

    /** R3 人物在「首次登场章」之前就出场了。 */
    private void checkEarlyAppearance(List<ConsistencyDraft> found,
                                      List<ChapterCharacterEntity> links,
                                      Map<Long, CharacterEntity> characterById,
                                      Map<Long, ChapterEntity> chapterById) {
        for (ChapterCharacterEntity link : links) {
            CharacterEntity character = characterById.get(link.getCharacterId());
            ChapterEntity chapter = chapterById.get(link.getChapterId());
            if (character == null || chapter == null || character.getFirstChapter() == null) {
                continue;
            }
            Integer chapterNo = chapter.getChapterNo();
            if (chapterNo == null || chapterNo >= character.getFirstChapter()) {
                continue;
            }
            found.add(new ConsistencyDraft("RULE", chapter.getId(), chapterNo, chapter.getTitle(),
                    "CHARACTER_STATE", "P1",
                    "人物「" + character.getName() + "」首次登场是第 " + character.getFirstChapter()
                            + " 章，却出现在第 " + chapterNo + " 章",
                    "人物档案记「" + character.getName() + "」首次登场于第 " + character.getFirstChapter()
                            + " 章，但它已被登记为第 " + chapterNo + " 章的出场人物。"
                            + "首次登场章通常是 AI 生成大纲时顺手填的，改过章节顺序后容易对不上。",
                    "人物档案：首次登场=第 " + character.getFirstChapter() + " 章；第 " + chapterNo + " 章出场人物含此角色",
                    "把首次登场章改成 " + chapterNo + "，或从这一章的出场人物里去掉。"));
        }
    }

    /** R4 废弃 / 未使用的事件，却已经被写进了正文。 */
    private void checkEventStatus(List<ConsistencyDraft> found,
                                  List<ChapterEventEntity> links,
                                  Map<Long, TimelineEventEntity> eventById,
                                  Map<Long, ChapterEntity> chapterById) {
        for (ChapterEventEntity link : links) {
            TimelineEventEntity event = eventById.get(link.getEventId());
            ChapterEntity chapter = chapterById.get(link.getChapterId());
            if (event == null || chapter == null) {
                continue;
            }
            if (chapter.getWordCount() == null || chapter.getWordCount() <= 0) {
                continue;
            }
            String status = event.getStatus();
            if (!"ABANDONED".equalsIgnoreCase(status) && !"UNUSED".equalsIgnoreCase(status)) {
                continue;
            }
            boolean abandoned = "ABANDONED".equalsIgnoreCase(status);
            String label = NovelLabels.label(NovelLabels.EVENT_STATUS, status);
            found.add(new ConsistencyDraft("RULE", chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                    "TIMELINE", abandoned ? "P1" : "P2",
                    "事件「" + event.getTitle() + "」状态是" + label + "，却挂在第 " + chapter.getChapterNo() + " 章",
                    "第 " + chapter.getChapterNo() + " 章「" + chapter.getTitle() + "」已有正文，"
                            + "它关联的事件「" + event.getTitle() + "」状态却是" + label + "。"
                            + (abandoned ? "废弃的事件不该再出现在正文里。"
                            : "事件没标成进行中 / 已完成，多半是分析时没同步状态。"),
                    "第 " + chapter.getChapterNo() + " 章（" + chapter.getWordCount() + " 字）关联事件「"
                            + event.getTitle() + "」，状态=" + label,
                    abandoned ? "把该事件从这一章的关联里去掉，或改为进行中。"
                            : "重跑这一章的分析，让它把事件状态同步成已完成。"));
        }
    }

    /** R5 章号重复、卷内断号、排序值与卷号章号不一致。 */
    private void checkStructure(List<ConsistencyDraft> found, List<ChapterEntity> chapters) {
        // 5a 章号重复
        Map<Integer, List<ChapterEntity>> byNo = new LinkedHashMap<>();
        for (ChapterEntity chapter : chapters) {
            if (chapter.getChapterNo() == null) {
                continue;
            }
            byNo.computeIfAbsent(chapter.getChapterNo(), key -> new ArrayList<>()).add(chapter);
        }
        for (Map.Entry<Integer, List<ChapterEntity>> entry : byNo.entrySet()) {
            if (entry.getValue().size() < 2) {
                continue;
            }
            StringBuilder names = new StringBuilder();
            for (ChapterEntity chapter : entry.getValue()) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append('「').append(chapter.getTitle()).append('」');
            }
            found.add(new ConsistencyDraft("RULE", entry.getValue().get(0).getId(), entry.getKey(),
                    entry.getValue().get(0).getTitle(), "STRUCTURE", "P0",
                    "第 " + entry.getKey() + " 章有 " + entry.getValue().size() + " 个章节在用同一个章号",
                    "下面这些章节的章号都是 " + entry.getKey() + "：" + names
                            + "。章号是排序与跳转的依据，重复会导致导出与前后章判断取到错的那一章。",
                    "重复章号：" + entry.getKey() + "，涉及 " + entry.getValue().size() + " 个章节",
                    "改掉多余的章号，让每章的章号唯一。"));
        }

        // 5b 全书断号
        //
        // 注意：这里查的是**全书**章号，不是「卷内从 1 开始」。系统的章号是全书递增的
        // （新增章节取全书最大章号 + 1，AI 生成大纲也一路往上加），卷只是分组。
        // 若按卷判断「卷内必须从 1 连续」，那么第一卷 1~5、第二卷 6~10 的正常分卷书
        // 会被判成「第二卷缺第 1~5 章」——分卷一旦可管理，这个误报必然出现。
        List<Integer> allNos = new ArrayList<>();
        for (ChapterEntity chapter : chapters) {
            if (chapter.getChapterNo() != null) {
                allNos.add(chapter.getChapterNo());
            }
        }
        List<Integer> sorted = new ArrayList<>(allNos);
        sorted.sort(Comparator.naturalOrder());
        if (new HashSet<>(sorted).size() != sorted.size()) {
            // 重复已由 5a 报出，此时再判断号只会给出误导性的缺口清单
        } else if (!sorted.isEmpty()) {
            List<Integer> missing = new ArrayList<>();
            int expected = 1;
            for (Integer no : sorted) {
                while (expected < no) {
                    missing.add(expected);
                    expected++;
                }
                expected = no + 1;
            }
            if (!missing.isEmpty()) {
                StringBuilder list = new StringBuilder();
                int shown = 0;
                for (Integer no : missing) {
                    if (shown >= 8) {
                        list.append(" 等 ").append(missing.size()).append(" 个");
                        break;
                    }
                    if (list.length() > 0) {
                        list.append("、");
                    }
                    list.append(no);
                    shown++;
                }
                found.add(new ConsistencyDraft("RULE", null, null, null, "STRUCTURE", "P1",
                        "全书章号不连续，缺第 " + list + " 章",
                        "现有章号从 1 开始应该连续，实际缺了第 " + list + " 章。"
                                + "删章会造成断号；导出与「上一章 / 下一章」的衔接会因此错位。",
                        "现有章号：" + sorted,
                        "补上缺失的章节，或把后面的章号依次前移。"));
            }
        }

        // 5c 排序值异常
        for (ChapterEntity chapter : chapters) {
            if (chapter.getChapterNo() == null || chapter.getVolumeNo() == null || chapter.getSortNo() == null) {
                continue;
            }
            int expected = chapter.getVolumeNo() * CHAPTER_SORT_UNIT + chapter.getChapterNo();
            if (expected == chapter.getSortNo()) {
                continue;
            }
            found.add(new ConsistencyDraft("RULE", chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                    "STRUCTURE", "P2",
                    "第 " + chapter.getChapterNo() + " 章的排序值与卷号章号算出来的不一致",
                    "这一章的排序值是 " + chapter.getSortNo() + "，按「卷号 × 1000 + 章号」应为 " + expected + "。"
                            + "排序值决定列表与导出的先后顺序，错位会让这一章显示在错的地方。",
                    "卷号=" + chapter.getVolumeNo() + "｜章号=" + chapter.getChapterNo()
                            + "｜排序值=" + chapter.getSortNo() + "｜应为 " + expected,
                    "重新保存一次这一章，系统会按卷号与章号重算排序值。"));
        }
    }

    /** R6 有正文没大纲、或标了已完成却没正文。 */
    private void checkOutlineGap(List<ConsistencyDraft> found, List<ChapterEntity> chapters) {
        for (ChapterEntity chapter : chapters) {
            int words = chapter.getWordCount() == null ? 0 : chapter.getWordCount();
            boolean hasSummary = chapter.getSummary() != null && !chapter.getSummary().isBlank();
            if (words > 0 && !hasSummary) {
                found.add(new ConsistencyDraft("RULE", chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                        "OUTLINE_DRIFT", "P2",
                        "第 " + chapter.getChapterNo() + " 章有正文却没有大纲摘要",
                        "这一章已经写了 " + words + " 字，但大纲摘要是空的。"
                                + "没有摘要的话，后面几章生成正文时读不到这一章发生了什么，剧情容易接不上。",
                        "第 " + chapter.getChapterNo() + " 章：正文 " + words + " 字，大纲摘要为空",
                        "补一段本章摘要（一两句讲清发生了什么即可）。"));
                continue;
            }
            if ("DONE".equalsIgnoreCase(chapter.getStatus()) && words == 0) {
                found.add(new ConsistencyDraft("RULE", chapter.getId(), chapter.getChapterNo(), chapter.getTitle(),
                        "OUTLINE_DRIFT", "P1",
                        "第 " + chapter.getChapterNo() + " 章标记为已完成，却还没有正文",
                        "这一章状态是已完成，正文字数却是 0。已完成的章节在导出与统计里会被算进去，"
                                + "空章会让总字数与章节数对不上。",
                        "第 " + chapter.getChapterNo() + " 章：状态=已完成，正文字数=0",
                        "补写正文，或把状态改回草稿 / 写作中。"));
            }
        }
    }

    /** R7 有正文却没分析，而它后面的章节已经分析过（顺序断档）。 */
    private void checkArchiveGap(List<ConsistencyDraft> found, List<ChapterEntity> chapters,
                                 Set<Long> analyzedChapterIds) {
        boolean laterAnalyzed = false;
        List<ChapterEntity> missing = new ArrayList<>();
        // 从后往前扫：只要后面有分析过的章节，前面这些没分析的就是断档
        List<ChapterEntity> ordered = new ArrayList<>(chapters);
        ordered.sort(Comparator
                .comparing((ChapterEntity chapter) -> chapter.getVolumeNo() == null ? 0 : chapter.getVolumeNo())
                .thenComparing(chapter -> chapter.getChapterNo() == null ? 0 : chapter.getChapterNo()));
        for (int i = ordered.size() - 1; i >= 0; i--) {
            ChapterEntity chapter = ordered.get(i);
            boolean analyzed = analyzedChapterIds.contains(chapter.getId());
            if (analyzed) {
                laterAnalyzed = true;
                continue;
            }
            if (!laterAnalyzed) {
                continue;
            }
            if (chapter.getWordCount() == null || chapter.getWordCount() <= 0) {
                continue;
            }
            missing.add(chapter);
        }
        if (missing.isEmpty()) {
            return;
        }
        missing.sort(Comparator.comparing(ChapterEntity::getChapterNo));
        StringBuilder list = new StringBuilder();
        int shown = 0;
        for (ChapterEntity chapter : missing) {
            if (shown >= 8) {
                list.append(" 等 ").append(missing.size()).append(" 章");
                break;
            }
            if (list.length() > 0) {
                list.append("、");
            }
            list.append(chapter.getChapterNo());
            shown++;
        }
        found.add(new ConsistencyDraft("RULE", missing.get(0).getId(), missing.get(0).getChapterNo(),
                missing.get(0).getTitle(), "ARCHIVE", "P1",
                "第 " + list + " 章有正文却没有分析记录",
                "分析必须按章节顺序推进：这几章已经写了正文却没分析，而它们后面的章节已经分析过了。"
                        + "跳过的章节不会补建人物与物品状态，后面几章的判断依据就是缺的。",
                "缺分析的章节：第 " + list + " 章",
                "按从第 " + missing.get(0).getChapterNo() + " 章开始的顺序依次补跑分析。"));
    }

    /** R8 关联指向已删除的人物 / 事件。 */
    private void checkDangling(List<ConsistencyDraft> found,
                               List<ChapterCharacterEntity> chapterCharacters,
                               List<ChapterEventEntity> chapterEvents,
                               Map<Long, CharacterEntity> characterById,
                               Map<Long, TimelineEventEntity> eventById,
                               Map<Long, ChapterEntity> chapterById) {
        Set<Long> reported = new HashSet<>();
        for (ChapterCharacterEntity link : chapterCharacters) {
            if (characterById.containsKey(link.getCharacterId())) {
                continue;
            }
            if (!reported.add(link.getCharacterId())) {
                continue;
            }
            ChapterEntity chapter = chapterById.get(link.getChapterId());
            found.add(new ConsistencyDraft("RULE", link.getChapterId(),
                    chapter == null ? null : chapter.getChapterNo(),
                    chapter == null ? null : chapter.getTitle(),
                    "STRUCTURE", "P0",
                    "有章节的出场人物指向一个已经被删除的角色",
                    "章节 ↔ 人物的关联表里有一条记录指向 id 为 " + link.getCharacterId()
                            + " 的角色，但人物表里已经没有它（多半是被删除了）。"
                            + "这条关联会在生成正文时被当作上下文注入，模型可能凭空写出一个不存在的人。",
                    "悬空关联：chapter_id=" + link.getChapterId() + "，character_id=" + link.getCharacterId(),
                    "重新保存一次这一章的出场人物，把失效的条目去掉。"));
        }
        reported.clear();
        for (ChapterEventEntity link : chapterEvents) {
            if (eventById.containsKey(link.getEventId())) {
                continue;
            }
            if (!reported.add(link.getEventId())) {
                continue;
            }
            ChapterEntity chapter = chapterById.get(link.getChapterId());
            found.add(new ConsistencyDraft("RULE", link.getChapterId(),
                    chapter == null ? null : chapter.getChapterNo(),
                    chapter == null ? null : chapter.getTitle(),
                    "STRUCTURE", "P0",
                    "有章节的关联事件指向一个已经被删除的事件",
                    "章节 ↔ 事件的关联表里有一条记录指向 id 为 " + link.getEventId()
                            + " 的事件，但事件表里已经没有它。生成正文时会把它当成本章要写的事，导致剧情凭空分叉。",
                    "悬空关联：chapter_id=" + link.getChapterId() + "，event_id=" + link.getEventId(),
                    "重新保存一次这一章的关联事件，把失效的条目去掉。"));
        }
    }

    /* ------------------------------------------------------------------ */

    /** 落库前统一截断，避免超长文本把插入打回。 */
    public static ConsistencyDraft clamp(ConsistencyDraft draft) {
        return new ConsistencyDraft(
                draft.source(),
                draft.chapterId(),
                draft.chapterNo(),
                clip(draft.chapterTitle(), 200),
                draft.issueType(),
                draft.severity(),
                clip(draft.title(), MAX_TITLE),
                clip(draft.detail(), MAX_DETAIL),
                clip(draft.evidence(), MAX_EVIDENCE),
                clip(draft.suggestion(), MAX_EVIDENCE));
    }

    private static String clip(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
