package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.modules.bookshelf.dto.AnalysisDraft;
import com.storyforge.modules.bookshelf.dto.AnalysisFacts;
import com.storyforge.modules.bookshelf.dto.ChapterAnalysisVO;
import com.storyforge.modules.bookshelf.entity.ChapterAnalysisEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEntity;
import com.storyforge.modules.bookshelf.entity.ChapterEventEntity;
import com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.entity.TimelineEventEntity;
import com.storyforge.modules.bookshelf.mapper.ChapterAnalysisMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterEventMapper;
import com.storyforge.modules.bookshelf.mapper.ChapterMapper;
import com.storyforge.modules.bookshelf.mapper.ChangeHistoryMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterAttributeMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterInventoryMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterStateMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.ForeshadowMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import com.storyforge.modules.bookshelf.mapper.TimelineEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 把分析结果写进库。
 *
 * <p>这里只做「已经解析干净」的数据落库：模型给的姓名 / 标题先映射成 ID，映射不上的丢弃并记 warning，
 * 因此任何模型幻觉都不会写进人物档案。人物与物品、势力的变更只记履历、不回改档案字段——
 * 档案由作者自己维护，履历用来提示「这一章之后设定该更新了」；事件状态是例外，收尾即置为已完成。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChapterAnalysisApplier {

    private final ChapterAnalysisMapper analysisMapper;
    private final ChangeHistoryMapper changeHistoryMapper;
    private final ChapterEventMapper chapterEventMapper;
    private final CharacterMapper characterMapper;
    private final ItemMapper itemMapper;
    private final FactionMapper factionMapper;
    private final TimelineEventMapper timelineEventMapper;
    private final CharacterAttributeMapper characterAttributeMapper;
    private final CharacterInventoryMapper characterInventoryMapper;
    private final ChapterMapper chapterMapper;
    private final ForeshadowMapper foreshadowMapper;
    private final CharacterStateMapper characterStateMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public ChapterAnalysisVO persist(AnalysisDraft draft) {
        ChapterEntity chapter = draft.getChapter();
        Long userId = UserContextHolder.requireUserId();
        Long novelId = chapter.getNovelId();
        List<String> warnings = new ArrayList<>(draft.getWarnings() == null ? List.of() : draft.getWarnings());

        ChapterAnalysisEntity row = new ChapterAnalysisEntity();
        row.setUserId(userId);
        row.setNovelId(novelId);
        row.setChapterId(chapter.getId());
        row.setChapterNo(chapter.getChapterNo());
        row.setChapterTitle(chapter.getTitle());
        row.setWordCount(draft.getLocalSignals() == null ? 0 : wordCount(draft));
        row.setTargetWords(draft.getTargetWords());
        row.setWordPass(draft.isWordPass() ? 1 : 0);
        row.setOutlineScore(draft.getOutlineScore());
        row.setAiScore(draft.getAiScore());
        row.setModel(draft.getModel());
        row.setLatencyMs(draft.getLatencyMs());
        row.setDeleted(0);
        row.setVersion(0);
        analysisMapper.insert(row);

        AnalysisFacts facts = draft.getFacts() == null ? AnalysisFacts.empty() : draft.getFacts();

        List<CharacterEntity> characters = characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, novelId)
                .orderByAsc(CharacterEntity::getSortNo));
        Map<String, CharacterEntity> characterIndex = indexCharacters(characters);
        List<ItemEntity> items = itemMapper.selectList(Wrappers.<ItemEntity>lambdaQuery()
                .eq(ItemEntity::getNovelId, novelId));
        Map<String, ItemEntity> itemIndex = indexItems(items);
        List<FactionEntity> factions = factionMapper.selectList(Wrappers.<FactionEntity>lambdaQuery()
                .eq(FactionEntity::getNovelId, novelId));
        Map<String, FactionEntity> factionIndex = indexFactions(factions);
        Map<Long, String> factionNames = new HashMap<>();
        factions.forEach(faction -> factionNames.put(faction.getId(), faction.getName()));

        List<ChapterAnalysisVO.NewCharacter> created = createCharacters(novelId, chapter, facts.newCharacters(),
                characterIndex, warnings);
        List<ChapterAnalysisVO.ChangeRecord> changes = new ArrayList<>();
        changes.addAll(applyChanges("CHARACTER", chapter, row.getId(), facts.characterChanges(), characterIndex,
                factionNames, warnings));
        changes.addAll(applyChanges("ITEM", chapter, row.getId(), facts.itemChanges(), itemIndex,
                factionNames, warnings));
        changes.addAll(applyChanges("FACTION", chapter, row.getId(), facts.factionChanges(), factionIndex,
                factionNames, warnings));
        changes.addAll(applyAttributeChanges(chapter, row.getId(), facts.attributeChanges(), characterIndex, warnings));
        changes.addAll(applyInventoryChanges(chapter, row.getId(), facts.inventoryChanges(), characterIndex, warnings));
        List<ChapterAnalysisVO.EventChange> events = applyEventChanges(chapter, row.getId(),
                facts.eventChanges(), warnings);

        // 叙事层：事实摘要 / 角色状态 / 伏笔。这三样是「下一章能读到什么」的来源，
        // 与上面的档案变更分开处理——上面改的是档案，这里改的是**记忆**。
        // 不塞进 ChapterAnalysisVO：历史 result_json 是按旧字段序列化的，
        // 给 record 加字段会让老报告反序列化失败；这三样走独立的 /facts 接口读。
        applyFactSummary(chapter, facts.factSummary(), warnings);
        int stateCount = applyCharacterStates(novelId, chapter, facts.characterStates(), characterIndex, warnings);
        int[] foreshadowCount = applyForeshadows(
                novelId, chapter, facts.foreshadowPlanted(), facts.foreshadowPaid(), warnings);

        ChapterAnalysisVO report = new ChapterAnalysisVO(
                row.getId(),
                chapter.getId(),
                chapter.getChapterNo(),
                chapter.getTitle(),
                wordCount(draft),
                draft.getTargetWords(),
                draft.getMinWords(),
                draft.getMaxWords(),
                draft.isWordPass(),
                draft.getWordLabel(),
                draft.getWordComment(),
                draft.getOutlineScore(),
                draft.getOutlineVerdict(),
                draft.getOutlineHits(),
                draft.getOutlineMisses(),
                draft.getOutlineSuggestions(),
                draft.getAiScore(),
                draft.getAiVerdict(),
                draft.getAiSignals(),
                draft.getAiExamples(),
                draft.getLocalSignals(),
                created,
                changes,
                events,
                warnings,
                draft.getModel(),
                draft.getLatencyMs(),
                row.getCreatedAt());

        try {
            row.setResultJson(objectMapper.writeValueAsString(report));
        } catch (Exception ex) {
            log.warn("[analysis] serialize report failed: {}", ex.getMessage());
            row.setResultJson(null);
        }
        analysisMapper.updateById(row);

        log.info("[analysis] persisted id={} chapterId={} outline={} ai={} newChars={} changes={} events={} states={} forePlanted={} forePaid={}",
                row.getId(), chapter.getId(), draft.getOutlineScore(), draft.getAiScore(),
                created.size(), changes.size(), events.size(),
                stateCount, foreshadowCount[0], foreshadowCount[1]);
        return report;
    }

    private int wordCount(AnalysisDraft draft) {
        return com.storyforge.common.util.WordCounts.countChinese(draft.getChapter().getContent());
    }

    /* ------------------------------------------------------------------ */
    /* 新人物                                                             */
    /* ------------------------------------------------------------------ */

    private List<ChapterAnalysisVO.NewCharacter> createCharacters(Long novelId,
                                                                 ChapterEntity chapter,
                                                                 List<AnalysisFacts.NewCharacter> candidates,
                                                                 Map<String, CharacterEntity> index,
                                                                 List<String> warnings) {
        List<ChapterAnalysisVO.NewCharacter> created = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return created;
        }
        int maxSort = index.values().stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0);
        // 新出场的人物不能顶掉已有主角，避免主线视角被抢
        boolean hasProtagonist = index.values().stream()
                .anyMatch(item -> "PROTAGONIST".equals(item.getRolePos()));
        for (AnalysisFacts.NewCharacter candidate : candidates) {
            String name = trim(candidate.name());
            if (name.isEmpty()) {
                continue;
            }
            String key = nameKey(name);
            if (index.containsKey(key)) {
                warnings.add("「" + name + "」已在人物档案中，未重复添加");
                continue;
            }
            String rolePos = normalizeRole(candidate.rolePos());
            if ("PROTAGONIST".equals(rolePos) && hasProtagonist) {
                rolePos = "SUPPORTING";
            }
            CharacterEntity entity = new CharacterEntity();
            entity.setUserId(UserContextHolder.requireUserId());
            entity.setNovelId(novelId);
            entity.setName(truncate(name, 64));
            entity.setRolePos(rolePos);
            entity.setIdentity(truncate(trim(candidate.identity()), 255));
            entity.setGender(truncate(trim(candidate.gender()), 16));
            entity.setAgeDesc(truncate(trim(candidate.ageDesc()), 32));
            entity.setLifeStatus("ALIVE");
            entity.setFirstChapter(chapter.getChapterNo());
            entity.setSortNo(maxSort + 1);
            Map<String, String> profile = new LinkedHashMap<>();
            putIfText(profile, "appearance", candidate.appearance());
            putIfText(profile, "personality", candidate.personality());
            putIfText(profile, "motivation", candidate.motivation());
            try {
                entity.setProfileJson(profile.isEmpty() ? "" : objectMapper.writeValueAsString(profile));
            } catch (Exception ex) {
                entity.setProfileJson("");
            }
            entity.setDeleted(0);
            entity.setVersion(0);
            characterMapper.insert(entity);
            index.put(key, entity);
            maxSort = entity.getSortNo();

            created.add(new ChapterAnalysisVO.NewCharacter(entity.getId(), entity.getName(), entity.getIdentity(),
                    entity.getRolePos(), NovelLabels.label(NovelLabels.ROLE, entity.getRolePos()),
                    truncate(trim(candidate.reason()), 200)));
        }
        return created;
    }

    /* ------------------------------------------------------------------ */
    /* 变更履历                                                           */
    /* ------------------------------------------------------------------ */

    private <T> List<ChapterAnalysisVO.ChangeRecord> applyChanges(String entityType,
                                                                 ChapterEntity chapter,
                                                                 Long analysisId,
                                                                 List<AnalysisFacts.Change> changes,
                                                                 Map<String, T> index,
                                                                 Map<Long, String> factionNames,
                                                                 List<String> warnings) {
        List<ChapterAnalysisVO.ChangeRecord> records = new ArrayList<>();
        if (changes == null || changes.isEmpty()) {
            return records;
        }
        for (AnalysisFacts.Change change : changes) {
            String rawName = trim(change.name());
            if (rawName.isEmpty()) {
                continue;
            }
            T found = index.get(nameKey(rawName));
            if (found == null) {
                warnings.add("「" + rawName + "」不在" + NovelLabels.entityTypeLabel(entityType)
                        + "档案里，已跳过一条变更");
                continue;
            }
            ChangeTarget target = resolveTarget(entityType, found, factionNames);
            String fieldKey = resolveFieldKey(entityType, change.field());
            if (fieldKey == null) {
                warnings.add("「" + rawName + "」的变更字段「" + change.field() + "」不受支持，已跳过");
                continue;
            }
            String after = normalizeAfter(entityType, fieldKey, change.after());
            if (after == null) {
                warnings.add("「" + rawName + "」的" + NovelLabels.fieldLabel(entityType, fieldKey)
                        + "取值「" + trim(change.after()) + "」无法识别，已跳过");
                continue;
            }
            String before = target.beforeValue(fieldKey);
            if (before == null || before.isBlank()) {
                before = "—";
            }
            if (before.equals(after)) {
                warnings.add("「" + rawName + "」的" + NovelLabels.fieldLabel(entityType, fieldKey)
                        + "没有变化，已忽略");
                continue;
            }

            ChangeHistoryEntity entity = changeHistoryMapper.selectOne(Wrappers.<ChangeHistoryEntity>lambdaQuery()
                    .eq(ChangeHistoryEntity::getChapterId, chapter.getId())
                    .eq(ChangeHistoryEntity::getEntityType, entityType)
                    .eq(ChangeHistoryEntity::getEntityId, target.id())
                    .eq(ChangeHistoryEntity::getFieldKey, fieldKey)
                    .last("LIMIT 1"));
            boolean insert = entity == null;
            if (insert) {
                entity = new ChangeHistoryEntity();
                entity.setUserId(UserContextHolder.requireUserId());
                entity.setNovelId(chapter.getNovelId());
                entity.setChapterId(chapter.getId());
                entity.setChapterNo(chapter.getChapterNo());
                entity.setChapterTitle(chapter.getTitle());
                entity.setEntityType(entityType);
                entity.setEntityId(target.id());
                entity.setEntityName(target.name());
                entity.setFieldKey(fieldKey);
                entity.setDeleted(0);
                entity.setVersion(0);
            }
            entity.setAnalysisId(analysisId);
            entity.setFieldLabel(NovelLabels.fieldLabel(entityType, fieldKey));
            entity.setBeforeState(truncate(before, 500));
            entity.setAfterState(truncate(after, 500));
            entity.setReason(truncate(trim(change.reason()), 500));
            entity.setEvidence(truncate(trim(change.evidence()), 500));
            if (insert) {
                changeHistoryMapper.insert(entity);
            } else {
                changeHistoryMapper.updateById(entity);
            }

            records.add(new ChapterAnalysisVO.ChangeRecord(entity.getId(), entityType,
                    NovelLabels.entityTypeLabel(entityType), target.id(), target.name(),
                    fieldKey, entity.getFieldLabel(), entity.getBeforeState(), entity.getAfterState(),
                    entity.getReason(), entity.getEvidence()));
        }
        return records;
    }

    private ChangeTarget resolveTarget(String entityType, Object entity, Map<Long, String> factionNames) {
        if (entity instanceof CharacterEntity character) {
            String faction = character.getFactionId() == null ? "无"
                    : factionNames.getOrDefault(character.getFactionId(), "无");
            Map<String, String> values = new LinkedHashMap<>();
            values.put("lifeStatus", NovelLabels.label(NovelLabels.LIFE, character.getLifeStatus()));
            values.put("identity", trim(character.getIdentity()));
            values.put("faction", faction);
            values.put("rolePos", NovelLabels.label(NovelLabels.ROLE, character.getRolePos()));
            return new ChangeTarget(character.getId(), character.getName(), values);
        }
        if (entity instanceof ItemEntity item) {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("status", NovelLabels.label(NovelLabels.ITEM_STATUS, item.getStatus()));
            values.put("owner", trim(item.getOwner()));
            values.put("quantity", item.getQuantity() == null ? "不计" : String.valueOf(item.getQuantity()));
            return new ChangeTarget(item.getId(), item.getName(), values);
        }
        FactionEntity faction = (FactionEntity) entity;
        Map<String, String> values = new LinkedHashMap<>();
        values.put("status", NovelLabels.label(NovelLabels.FACTION_STATUS, faction.getStatus()));
        values.put("scope", trim(faction.getScope()));
        return new ChangeTarget(faction.getId(), faction.getName(), values);
    }

    private record ChangeTarget(Long id, String name, Map<String, String> values) {
        String beforeValue(String fieldKey) {
            return values.get(fieldKey);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 属性 / 背包                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * 属性变更：与人物/物品「只写履历、不改档案」不同，属性是**状态快照**，
     * 这里必须同时更新当前值，否则下一次分析拿到的"原状态"就是错的。
     */
    private List<ChapterAnalysisVO.ChangeRecord> applyAttributeChanges(ChapterEntity chapter,
                                                                     Long analysisId,
                                                                     List<AnalysisFacts.Change> changes,
                                                                     Map<String, CharacterEntity> characterIndex,
                                                                     List<String> warnings) {
        List<ChapterAnalysisVO.ChangeRecord> records = new ArrayList<>();
        if (changes == null || changes.isEmpty()) {
            return records;
        }
        for (AnalysisFacts.Change change : changes) {
            String characterName = trim(change.name());
            String attrName = trim(change.field());
            String after = trim(change.after());
            if (characterName.isEmpty() || attrName.isEmpty() || after.isEmpty()) {
                continue;
            }
            CharacterEntity character = characterIndex.get(nameKey(characterName));
            if (character == null) {
                warnings.add("「" + characterName + "」不在人物档案里，属性变更已跳过");
                continue;
            }
            com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity attribute =
                    characterAttributeMapper.selectOne(
                            Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity>lambdaQuery()
                                    .eq(com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity::getCharacterId,
                                            character.getId())
                                    .eq(com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity::getName, attrName)
                                    .last("LIMIT 1"));
            String before = attribute == null || attribute.getValue() == null || attribute.getValue().isBlank()
                    ? "—" : attribute.getValue();
            if (before.equals(after)) {
                warnings.add("「" + characterName + "」的属性「" + attrName + "」没有变化，已忽略");
                continue;
            }
            if (attribute == null) {
                attribute = new com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity();
                attribute.setUserId(UserContextHolder.requireUserId());
                attribute.setNovelId(chapter.getNovelId());
                attribute.setCharacterId(character.getId());
                attribute.setName(truncate(attrName, 64));
                attribute.setValue(truncate(after, 64));
                attribute.setAttrType(isNumeric(after) ? "NUMBER" : "TEXT");
                attribute.setSortNo(nextAttributeSort(character.getId()));
                attribute.setDeleted(0);
                attribute.setVersion(0);
                characterAttributeMapper.insert(attribute);
            } else {
                attribute.setValue(truncate(after, 64));
                characterAttributeMapper.updateById(attribute);
            }
            records.add(upsertHistory("CHARACTER_ATTRIBUTE", character.getId(), character.getName(),
                    truncate(attrName, 64), before, after, change, chapter, analysisId));
        }
        return records;
    }

    /** 背包变更：ITEM / CURRENCY / MISC 一律按「条目名」定位，数量直接覆盖为本章结束后的值。 */
    private List<ChapterAnalysisVO.ChangeRecord> applyInventoryChanges(ChapterEntity chapter,
                                                                     Long analysisId,
                                                                     List<AnalysisFacts.Change> changes,
                                                                     Map<String, CharacterEntity> characterIndex,
                                                                     List<String> warnings) {
        List<ChapterAnalysisVO.ChangeRecord> records = new ArrayList<>();
        if (changes == null || changes.isEmpty()) {
            return records;
        }
        for (AnalysisFacts.Change change : changes) {
            String characterName = trim(change.name());
            String entryName = trim(change.field());
            String rawAfter = trim(change.after());
            if (characterName.isEmpty() || entryName.isEmpty() || rawAfter.isEmpty()) {
                continue;
            }
            CharacterEntity character = characterIndex.get(nameKey(characterName));
            if (character == null) {
                warnings.add("「" + characterName + "」不在人物档案里，背包变更已跳过");
                continue;
            }
            com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity entry =
                    characterInventoryMapper.selectOne(
                            Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaQuery()
                                    .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getCharacterId,
                                            character.getId())
                                    .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getName, entryName)
                                    .last("LIMIT 1"));
            // after 写成 LOST / CONSUMED 时是「状态变化」，不再当数量处理：丢了、用完了都是剧情里常见的事
            String statusValue = normalizeInventoryStatus(rawAfter);
            if (statusValue != null) {
                if (entry == null) {
                    warnings.add("「" + characterName + "」的背包里没有「" + entryName + "」，状态变更已跳过");
                    continue;
                }
                String beforeStatus = entry.getStatus() == null ? "HELD" : entry.getStatus();
                if (beforeStatus.equals(statusValue)) {
                    warnings.add("「" + characterName + "」的背包条目「" + entryName + "」状态没变，已忽略");
                    continue;
                }
                characterInventoryMapper.update(null,
                        Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaUpdate()
                                .set(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getStatus,
                                        statusValue)
                                .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getId, entry.getId()));
                records.add(upsertHistory("INVENTORY", character.getId(), character.getName(), truncate(entryName, 100),
                        NovelLabels.label(NovelLabels.INVENTORY_STATUS, beforeStatus),
                        NovelLabels.label(NovelLabels.INVENTORY_STATUS, statusValue),
                        change, chapter, analysisId));
                continue;
            }
            BigDecimal afterQty = parseQuantity(rawAfter);
            if (afterQty == null) {
                warnings.add("「" + characterName + "」的背包条目「" + entryName
                        + "」数量「" + rawAfter + "」不是数字，已跳过");
                continue;
            }
            String before = entry == null || entry.getQuantity() == null
                    ? "—" : entry.getQuantity().stripTrailingZeros().toPlainString();
            String after = afterQty.stripTrailingZeros().toPlainString();
            if (before.equals(after)) {
                warnings.add("「" + characterName + "」的背包条目「" + entryName + "」没有变化，已忽略");
                continue;
            }
            if (entry == null) {
                ItemEntity matched = itemMapper.selectOne(Wrappers.<ItemEntity>lambdaQuery()
                        .eq(ItemEntity::getNovelId, chapter.getNovelId())
                        .eq(ItemEntity::getName, entryName)
                        .last("LIMIT 1"));
                entry = new com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity();
                entry.setUserId(UserContextHolder.requireUserId());
                entry.setNovelId(chapter.getNovelId());
                entry.setCharacterId(character.getId());
                entry.setEntryType(matched == null ? "MISC" : "ITEM");
                entry.setItemId(matched == null ? null : matched.getId());
                entry.setName(truncate(entryName, 100));
                entry.setQuantity(afterQty);
                entry.setStatus("HELD");
                entry.setSortNo(nextInventorySort(character.getId()));
                entry.setDeleted(0);
                entry.setVersion(0);
                characterInventoryMapper.insert(entry);
            } else {
                // 数值型字段 updateById 会跳过 null，统一用 update wrapper 改数量
                characterInventoryMapper.update(null,
                        Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaUpdate()
                                .set(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getQuantity, afterQty)
                                .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getId, entry.getId()));
            }
            records.add(upsertHistory("INVENTORY", character.getId(), character.getName(),
                    truncate(entryName, 100), before, after, change, chapter, analysisId));
        }
        return records;
    }

    /** 履历 upsert：同一章同一对象同一字段只保留一条（重跑分析会覆盖而不是追加）。 */
    private ChapterAnalysisVO.ChangeRecord upsertHistory(String entityType,
                                                        Long entityId,
                                                        String entityName,
                                                        String fieldKey,
                                                        String before,
                                                        String after,
                                                        AnalysisFacts.Change change,
                                                        ChapterEntity chapter,
                                                        Long analysisId) {
        ChangeHistoryEntity entity = changeHistoryMapper.selectOne(Wrappers.<ChangeHistoryEntity>lambdaQuery()
                .eq(ChangeHistoryEntity::getChapterId, chapter.getId())
                .eq(ChangeHistoryEntity::getEntityType, entityType)
                .eq(ChangeHistoryEntity::getEntityId, entityId)
                .eq(ChangeHistoryEntity::getFieldKey, fieldKey)
                .last("LIMIT 1"));
        boolean insert = entity == null;
        if (insert) {
            entity = new ChangeHistoryEntity();
            entity.setUserId(UserContextHolder.requireUserId());
            entity.setNovelId(chapter.getNovelId());
            entity.setChapterId(chapter.getId());
            entity.setChapterNo(chapter.getChapterNo());
            entity.setChapterTitle(chapter.getTitle());
            entity.setEntityType(entityType);
            entity.setEntityId(entityId);
            entity.setEntityName(entityName);
            entity.setFieldKey(fieldKey);
            entity.setDeleted(0);
            entity.setVersion(0);
        }
        entity.setAnalysisId(analysisId);
        entity.setFieldLabel(NovelLabels.fieldLabel(entityType, fieldKey));
        entity.setBeforeState(truncate(before, 500));
        entity.setAfterState(truncate(after, 500));
        entity.setReason(truncate(trim(change.reason()), 500));
        entity.setEvidence(truncate(trim(change.evidence()), 500));
        if (insert) {
            changeHistoryMapper.insert(entity);
        } else {
            changeHistoryMapper.updateById(entity);
        }
        return new ChapterAnalysisVO.ChangeRecord(entity.getId(), entityType,
                NovelLabels.entityTypeLabel(entityType), entityId, entityName,
                fieldKey, entity.getFieldLabel(), entity.getBeforeState(), entity.getAfterState(),
                entity.getReason(), entity.getEvidence());
    }

    /** 从「20 枚」「约 20」这类文本里取出数字，取不到返回 null（宁可跳过也不猜）。 */
    private BigDecimal parseQuantity(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[^0-9.-]", "");
        if (cleaned.isBlank() || ".".equals(cleaned) || "-".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean isNumeric(String value) {
        return value != null && value.matches("-?\\d+(\\.\\d+)?");
    }

    private int nextAttributeSort(Long characterId) {
        return characterAttributeMapper.selectList(
                        Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity>lambdaQuery()
                                .eq(com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity::getCharacterId,
                                        characterId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    private int nextInventorySort(Long characterId) {
        return characterInventoryMapper.selectList(
                        Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity>lambdaQuery()
                                .eq(com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity::getCharacterId,
                                        characterId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    /* ------------------------------------------------------------------ */
    /* 事件状态                                                           */
    /* ------------------------------------------------------------------ */

    private List<ChapterAnalysisVO.EventChange> applyEventChanges(ChapterEntity chapter,
                                                                 Long analysisId,
                                                                 List<AnalysisFacts.EventChange> changes,
                                                                 List<String> warnings) {
        List<ChapterAnalysisVO.EventChange> result = new ArrayList<>();
        if (changes == null || changes.isEmpty()) {
            return result;
        }
        Set<Long> linked = chapterLinkedEventIds(chapter.getId());
        Map<Long, TimelineEventEntity> linkedEvents = new LinkedHashMap<>();
        if (!linked.isEmpty()) {
            timelineEventMapper.selectList(Wrappers.<TimelineEventEntity>lambdaQuery()
                            .eq(TimelineEventEntity::getNovelId, chapter.getNovelId())
                            .in(TimelineEventEntity::getId, linked))
                    .forEach(event -> linkedEvents.put(event.getId(), event));
        }
        Map<String, TimelineEventEntity> byTitle = new LinkedHashMap<>();
        linkedEvents.values().forEach(event -> byTitle.put(nameKey(event.getTitle()), event));

        for (AnalysisFacts.EventChange change : changes) {
            String title = trim(change.title());
            if (title.isEmpty()) {
                continue;
            }
            TimelineEventEntity event = byTitle.get(nameKey(title));
            if (event == null) {
                warnings.add("事件「" + title + "」不是本章关联事件，已跳过状态更新");
                continue;
            }
            String toStatus = normalizeEventStatus(change.status());
            if (toStatus == null) {
                warnings.add("事件「" + title + "」的状态取值无法识别，已跳过");
                continue;
            }
            String fromStatus = event.getStatus() == null ? "UNUSED" : event.getStatus();
            boolean changed = !fromStatus.equals(toStatus);
            if (changed) {
                event.setStatus(toStatus);
                timelineEventMapper.updateById(event);
            }
            result.add(new ChapterAnalysisVO.EventChange(event.getId(), event.getTitle(),
                    fromStatus, toStatus,
                    NovelLabels.label(NovelLabels.EVENT_STATUS, fromStatus),
                    NovelLabels.label(NovelLabels.EVENT_STATUS, toStatus),
                    truncate(trim(change.reason()), 500), changed));
        }
        return result;
    }

    private Set<Long> chapterLinkedEventIds(Long chapterId) {
        return chapterEventMapper.selectList(Wrappers.<ChapterEventEntity>lambdaQuery()
                        .eq(ChapterEventEntity::getChapterId, chapterId))
                .stream()
                .map(ChapterEventEntity::getEventId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private Map<String, CharacterEntity> indexCharacters(List<CharacterEntity> characters) {
        Map<String, CharacterEntity> index = new LinkedHashMap<>();
        characters.forEach(item -> index.putIfAbsent(nameKey(item.getName()), item));
        return index;
    }

    private Map<String, ItemEntity> indexItems(List<ItemEntity> items) {
        Map<String, ItemEntity> index = new LinkedHashMap<>();
        items.forEach(item -> index.putIfAbsent(nameKey(item.getName()), item));
        return index;
    }

    private Map<String, FactionEntity> indexFactions(List<FactionEntity> factions) {
        Map<String, FactionEntity> index = new LinkedHashMap<>();
        factions.forEach(item -> index.putIfAbsent(nameKey(item.getName()), item));
        return index;
    }

    /** 字段名放宽大小写：模型可能写成 LifeStatus 或 STATUS */
    private String resolveFieldKey(String entityType, String raw) {
        String value = trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        Map<String, String> fields = NovelLabels.FIELDS.getOrDefault(entityType, Map.of());
        if (fields.containsKey(value)) {
            return value;
        }
        for (String key : fields.keySet()) {
            if (key.equalsIgnoreCase(value)) {
                return key;
            }
        }
        return null;
    }

    private String normalizeRole(String raw) {        String value = trim(raw).toUpperCase(Locale.ROOT);
        return NovelLabels.ROLE.containsKey(value) ? value : "SUPPORTING";
    }

    private String normalizeEventStatus(String raw) {
        String value = trim(raw).toUpperCase(Locale.ROOT);
        if (NovelLabels.EVENT_STATUS.containsKey(value)) {
            return value;
        }
        for (Map.Entry<String, String> entry : NovelLabels.EVENT_STATUS.entrySet()) {
            if (entry.getValue().equals(trim(raw))) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * 背包条目的状态变化词。
     *
     * <p>只认「丢了 / 用完了」这类明确写法；返回 null 表示这个值不是状态，继续按数量处理，
     * 避免把「20」误判成状态。</p>
     */
    private String normalizeInventoryStatus(String raw) {
        String text = trim(raw);
        if (text.isEmpty()) {
            return null;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (NovelLabels.INVENTORY_STATUS.containsKey(upper)) {
            return upper;
        }
        return switch (text) {
            case "持有", "还在身上" -> "HELD";
            case "遗失", "丢失", "被抢走", "被夺走" -> "LOST";
            case "已消耗", "消耗", "用完", "耗尽", "吃掉", "用掉" -> "CONSUMED";
            default -> null;
        };
    }

    /** 枚举字段接受英文码或中文标签，统一成中文后比较与展示 */
    private String normalizeAfter(String entityType, String fieldKey, String raw) {        String value = trim(raw);
        if (value.isEmpty()) {
            return null;
        }
        Map<String, String> enums = switch (entityType + "." + fieldKey) {
            case "CHARACTER.lifeStatus" -> NovelLabels.LIFE;
            case "CHARACTER.rolePos" -> NovelLabels.ROLE;
            case "ITEM.status" -> NovelLabels.ITEM_STATUS;
            case "FACTION.status" -> NovelLabels.FACTION_STATUS;
            default -> Map.of();
        };
        if (enums.isEmpty()) {
            if ("quantity".equals(fieldKey)) {
                try {
                    return String.valueOf(Integer.parseInt(value));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            return truncate(value, 500);
        }
        String upper = value.toUpperCase(Locale.ROOT);
        if (enums.containsKey(upper)) {
            return enums.get(upper);
        }
        for (String label : enums.values()) {
            if (label.equals(value)) {
                return label;
            }
        }
        return null;
    }

    private String nameKey(String raw) {
        return trim(raw).replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private void putIfText(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

    /* ------------------------------------------------------------------ */
    /* 叙事层：事实摘要 / 角色状态 / 伏笔                                   */
    /* ------------------------------------------------------------------ */

    /**
     * 把本章事实摘要写回 {@code t_chapter.fact_summary}。
     *
     * <p>作者手工编辑过（{@code fact_source=MANUAL}）的一律不覆盖——作者的判断优先于模型。
     * 模型没产出时不写空值，只在报告里记一条提示，免得把上一次的摘要擦掉。</p>
     */
    private void applyFactSummary(ChapterEntity chapter, String factSummary, List<String> warnings) {
        if (factSummary == null || factSummary.isBlank()) {
            warnings.add("本章事实摘要缺失：模型没有给出可用的「实际发生」摘要，下一章只能靠大纲理解前情");
            return;
        }
        String value = factSummary.trim();
        chapterMapper.update(null, Wrappers.<ChapterEntity>lambdaUpdate()
                .eq(ChapterEntity::getId, chapter.getId())
                .and(w -> w.isNull(ChapterEntity::getFactSource)
                        .or()
                        .ne(ChapterEntity::getFactSource, ChapterEntity.FACT_SOURCE_MANUAL))
                .set(ChapterEntity::getFactSummary, value)
                .set(ChapterEntity::getFactSource, ChapterEntity.FACT_SOURCE_AI)
                .set(ChapterEntity::getUpdatedAt, java.time.LocalDateTime.now()));
        chapter.setFactSummary(value);
        chapter.setFactSource(ChapterEntity.FACT_SOURCE_AI);
    }

    /**
     * 落角色状态快照。
     *
     * <p>同一章重复分析时**更新**而不是再插一条，否则时间线里同章会出现多份互相矛盾的状态。
     * 姓名映射不到档案的整条丢弃——宁可少一条状态，也不给不存在的人建时间线。</p>
     */
    private int applyCharacterStates(Long novelId,
                                     ChapterEntity chapter,
                                     List<AnalysisFacts.CharacterState> states,
                                     Map<String, CharacterEntity> characterIndex,
                                     List<String> warnings) {
        if (states == null || states.isEmpty()) {
            return 0;
        }
        Long userId = UserContextHolder.requireUserId();
        int applied = 0;
        for (AnalysisFacts.CharacterState state : states) {
            CharacterEntity character = characterIndex.get(nameKey(state.name()));
            if (character == null) {
                warnings.add("角色状态里的「" + trim(state.name()) + "」不在人物档案里，已跳过");
                continue;
            }
            String location = nullIfBlank(state.location());
            String condition = nullIfBlank(state.condition());
            String mood = nullIfBlank(state.mood());
            String added = joinLines(state.knowsAdded());
            String hidden = joinLines(state.knowsHidden());

            com.storyforge.modules.bookshelf.entity.CharacterStateEntity existing = characterStateMapper.selectOne(
                    Wrappers.<com.storyforge.modules.bookshelf.entity.CharacterStateEntity>lambdaQuery()
                            .eq(com.storyforge.modules.bookshelf.entity.CharacterStateEntity::getNovelId, novelId)
                            .eq(com.storyforge.modules.bookshelf.entity.CharacterStateEntity::getCharacterId,
                                    character.getId())
                            .eq(com.storyforge.modules.bookshelf.entity.CharacterStateEntity::getChapterNo,
                                    chapter.getChapterNo())
                            .last("LIMIT 1"));
            if (existing == null) {
                com.storyforge.modules.bookshelf.entity.CharacterStateEntity entity =
                        new com.storyforge.modules.bookshelf.entity.CharacterStateEntity();
                entity.setUserId(userId);
                entity.setNovelId(novelId);
                entity.setChapterNo(chapter.getChapterNo());
                entity.setChapterId(chapter.getId());
                entity.setCharacterId(character.getId());
                entity.setLocation(location);
                entity.setConditionText(condition);
                entity.setMood(mood);
                entity.setKnowsAdded(added);
                entity.setKnowsHidden(hidden);
                entity.setDeleted(0);
                entity.setVersion(0);
                characterStateMapper.insert(entity);
            } else {
                existing.setLocation(location);
                existing.setConditionText(condition);
                existing.setMood(mood);
                existing.setKnowsAdded(added);
                existing.setKnowsHidden(hidden);
                characterStateMapper.updateById(existing);
            }
            applied++;
        }
        return applied;
    }

    /**
     * 落伏笔台账：先登记新埋的，再处理本章回收的。
     *
     * <p>标题在一本书内唯一（忽略大小写与空白），重复登记直接跳过——
     * 模型很容易把「同一封信」在相邻几章各报一次，提示词里说了不要重复，这里再兜一次底。
     * 回收时匹配不到标题的不报错，只记一条提示：模型可能换了叫法，作者可以从台账里手工标记。</p>
     *
     * @return {@code [新登记数, 本次回收数]}
     */
    private int[] applyForeshadows(Long novelId,
                                   ChapterEntity chapter,
                                   List<AnalysisFacts.ForeshadowPlanted> planted,
                                   List<AnalysisFacts.ForeshadowPaid> paid,
                                   List<String> warnings) {
        Long userId = UserContextHolder.requireUserId();
        Map<String, com.storyforge.modules.bookshelf.entity.ForeshadowEntity> index = new LinkedHashMap<>();
        for (com.storyforge.modules.bookshelf.entity.ForeshadowEntity row : foreshadowMapper.selectList(
                Wrappers.<com.storyforge.modules.bookshelf.entity.ForeshadowEntity>lambdaQuery()
                        .eq(com.storyforge.modules.bookshelf.entity.ForeshadowEntity::getNovelId, novelId))) {
            index.putIfAbsent(nameKey(row.getTitle()), row);
        }

        int plantedCount = 0;
        if (planted != null) {
            for (AnalysisFacts.ForeshadowPlanted item : planted) {
                String key = nameKey(item.title());
                if (key.isEmpty() || index.containsKey(key)) {
                    continue;
                }
                com.storyforge.modules.bookshelf.entity.ForeshadowEntity entity =
                        new com.storyforge.modules.bookshelf.entity.ForeshadowEntity();
                entity.setUserId(userId);
                entity.setNovelId(novelId);
                entity.setTitle(truncate(item.title(), 200));
                entity.setContent(nullIfBlank(item.content()));
                entity.setPlantedChapterNo(chapter.getChapterNo());
                entity.setPlantedChapterId(chapter.getId());
                entity.setPlannedPayoffChapter(item.plannedPayoffChapter());
                entity.setStatus(com.storyforge.modules.bookshelf.entity.ForeshadowEntity.STATUS_OPEN);
                entity.setDeleted(0);
                entity.setVersion(0);
                foreshadowMapper.insert(entity);
                index.put(key, entity);
                plantedCount++;
            }
        }

        int paidCount = 0;
        if (paid != null) {
            for (AnalysisFacts.ForeshadowPaid item : paid) {
                String rawTitle = trim(item.title());
                com.storyforge.modules.bookshelf.entity.ForeshadowEntity entity = index.get(nameKey(rawTitle));
                if (entity == null) {
                    warnings.add("模型说本章回收了「" + truncate(rawTitle, 40)
                            + "」，但台账里没有这条伏笔，已跳过（可到「伏笔」页手工标记）");
                    continue;
                }
                if (com.storyforge.modules.bookshelf.entity.ForeshadowEntity.STATUS_PAID.equals(entity.getStatus())) {
                    continue;
                }
                entity.setStatus(com.storyforge.modules.bookshelf.entity.ForeshadowEntity.STATUS_PAID);
                entity.setPayoffChapterNo(chapter.getChapterNo());
                entity.setPayoffChapterId(chapter.getId());
                foreshadowMapper.updateById(entity);
                paidCount++;
            }
        }
        return new int[]{plantedCount, paidCount};
    }

    /** {@code null} / 空串 / 全空白 → {@code null}；否则返回去空白后的原值 */
    private String nullIfBlank(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 多条认知信息按行拼接（一条一行，便于前端按行渲染与人工阅读） */
    private String joinLines(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        String joined = items.stream()
                .map(this::trim)
                .filter(text -> !text.isEmpty())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        return joined.isEmpty() ? null : joined;
    }
}
