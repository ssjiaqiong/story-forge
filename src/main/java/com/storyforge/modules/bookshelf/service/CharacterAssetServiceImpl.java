package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.bookshelf.dto.AttributeRequest;
import com.storyforge.modules.bookshelf.dto.AttributeVO;
import com.storyforge.modules.bookshelf.dto.InventoryRequest;
import com.storyforge.modules.bookshelf.dto.InventoryVO;
import com.storyforge.modules.bookshelf.entity.CharacterAttributeEntity;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.CharacterInventoryEntity;
import com.storyforge.modules.bookshelf.entity.ItemEntity;
import com.storyforge.modules.bookshelf.mapper.CharacterAttributeMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterInventoryMapper;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ItemMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人物资产服务：自定义属性 + 背包。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>属性只存“当前值”，历史变化由章节分析写入 {@code t_change_history}；</li>
 *   <li>背包的 ITEM 型只保存 {@code item_id} + 名称冗余，描述与说明始终读物品档案，避免两处维护；</li>
 *   <li>重复校验优先靠数据库部分唯一索引兜底，这里先查一次给出可读提示。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CharacterAssetServiceImpl implements CharacterAssetService {

    private static final Map<String, String> ENTRY_LABELS = Map.of(
            "ITEM", "物品",
            "CURRENCY", "货币",
            "MISC", "物资");

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "HELD", "持有",
            "LOST", "遗失",
            "CONSUMED", "已消耗");

    private final CharacterAttributeMapper attributeMapper;
    private final CharacterInventoryMapper inventoryMapper;
    private final CharacterMapper characterMapper;
    private final ItemMapper itemMapper;

    /* ------------------------------------------------------------------ */
    /* 属性                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public List<AttributeVO> listAttributes(Long characterId) {
        requireCharacter(characterId);
        return attributeMapper.selectList(Wrappers.<CharacterAttributeEntity>lambdaQuery()
                        .eq(CharacterAttributeEntity::getCharacterId, characterId)
                        .orderByAsc(CharacterAttributeEntity::getSortNo)
                        .orderByAsc(CharacterAttributeEntity::getId))
                .stream()
                .map(this::toAttributeVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttributeVO createAttribute(Long characterId, AttributeRequest request) {
        CharacterEntity character = requireCharacter(characterId);
        String name = request.getName().trim();
        Long exists = attributeMapper.selectCount(Wrappers.<CharacterAttributeEntity>lambdaQuery()
                .eq(CharacterAttributeEntity::getCharacterId, characterId)
                .eq(CharacterAttributeEntity::getName, name));
        if (exists != null && exists > 0) {
            throw BizException.of(ErrorCode.ATTRIBUTE_EXISTS, "属性「" + name + "」已存在");
        }

        CharacterAttributeEntity entity = new CharacterAttributeEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(character.getNovelId());
        entity.setCharacterId(characterId);
        entity.setName(name);
        entity.setValue(trimToNull(request.getValue()));
        entity.setUnit(trimToNull(request.getUnit()));
        entity.setAttrType(hasText(request.getAttrType()) ? request.getAttrType() : "NUMBER");
        entity.setNote(trimToNull(request.getNote()));
        entity.setSortNo(request.getSortNo() == null ? nextAttributeSort(characterId) : request.getSortNo());
        entity.setDeleted(0);
        entity.setVersion(0);
        attributeMapper.insert(entity);
        log.info("[attribute] created characterId={} name={} value={}", characterId, name, entity.getValue());
        return toAttributeVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AttributeVO updateAttribute(Long attributeId, AttributeRequest request) {
        CharacterAttributeEntity entity = requireAttribute(attributeId);
        String name = request.getName() == null ? entity.getName() : request.getName().trim();
        if (!hasText(name)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "属性名不能为空");
        }
        if (!name.equals(entity.getName())) {
            Long exists = attributeMapper.selectCount(Wrappers.<CharacterAttributeEntity>lambdaQuery()
                    .eq(CharacterAttributeEntity::getCharacterId, entity.getCharacterId())
                    .eq(CharacterAttributeEntity::getName, name)
                    .ne(CharacterAttributeEntity::getId, attributeId));
            if (exists != null && exists > 0) {
                throw BizException.of(ErrorCode.ATTRIBUTE_EXISTS, "属性「" + name + "」已存在");
            }
            entity.setName(name);
        }
        // 空串而非 null：updateById 跳过 null，"清空单位/备注"会失效
        if (request.getValue() != null) {
            entity.setValue(request.getValue().trim());
        }
        if (request.getUnit() != null) {
            entity.setUnit(request.getUnit().trim());
        }
        if (hasText(request.getAttrType())) {
            entity.setAttrType(request.getAttrType());
        }
        if (request.getNote() != null) {
            entity.setNote(request.getNote().trim());
        }
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
        }
        attributeMapper.updateById(entity);
        return toAttributeVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteAttribute(Long attributeId) {
        requireAttribute(attributeId);
        attributeMapper.deleteById(attributeId);
    }

    /* ------------------------------------------------------------------ */
    /* 背包                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public List<InventoryVO> listInventory(Long characterId) {
        requireCharacter(characterId);
        List<CharacterInventoryEntity> rows = inventoryMapper.selectList(
                Wrappers.<CharacterInventoryEntity>lambdaQuery()
                        .eq(CharacterInventoryEntity::getCharacterId, characterId)
                        .orderByAsc(CharacterInventoryEntity::getSortNo)
                        .orderByAsc(CharacterInventoryEntity::getId));
        Map<Long, ItemEntity> items = itemMap(rows);
        return rows.stream().map(row -> toInventoryVO(row, items.get(row.getItemId()))).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryVO createInventory(Long characterId, InventoryRequest request) {
        CharacterEntity character = requireCharacter(characterId);
        String entryType = hasText(request.getEntryType()) ? request.getEntryType() : "MISC";
        CharacterInventoryEntity entity = new CharacterInventoryEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(character.getNovelId());
        entity.setCharacterId(characterId);
        entity.setEntryType(entryType);
        entity.setStatus(hasText(request.getStatus()) ? request.getStatus() : "HELD");
        entity.setQuantity(request.getQuantity());
        entity.setUnit(trimToNull(request.getUnit()));
        entity.setNote(trimToNull(request.getNote()));
        entity.setSortNo(request.getSortNo() == null ? nextInventorySort(characterId) : request.getSortNo());
        entity.setDeleted(0);
        entity.setVersion(0);

        ItemEntity item = null;
        if ("ITEM".equals(entryType)) {
            item = requireItemOfNovel(request.getItemId(), character.getNovelId());
            Long exists = inventoryMapper.selectCount(Wrappers.<CharacterInventoryEntity>lambdaQuery()
                    .eq(CharacterInventoryEntity::getCharacterId, characterId)
                    .eq(CharacterInventoryEntity::getItemId, item.getId()));
            if (exists != null && exists > 0) {
                throw BizException.of(ErrorCode.INVENTORY_EXISTS, "「" + item.getName() + "」已经在背包里了");
            }
            entity.setItemId(item.getId());
            entity.setName(item.getName());
        } else {
            String name = trimToNull(request.getName());
            if (name == null) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "请填写货币或物资的名称");
            }
            Long exists = inventoryMapper.selectCount(Wrappers.<CharacterInventoryEntity>lambdaQuery()
                    .eq(CharacterInventoryEntity::getCharacterId, characterId)
                    .isNull(CharacterInventoryEntity::getItemId)
                    .eq(CharacterInventoryEntity::getName, name));
            if (exists != null && exists > 0) {
                throw BizException.of(ErrorCode.INVENTORY_EXISTS, "「" + name + "」已经在背包里了");
            }
            entity.setItemId(null);
            entity.setName(name);
        }
        inventoryMapper.insert(entity);
        log.info("[inventory] created characterId={} type={} name={} qty={}",
                characterId, entryType, entity.getName(), entity.getQuantity());
        return toInventoryVO(entity, item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public InventoryVO updateInventory(Long inventoryId, InventoryRequest request) {
        CharacterInventoryEntity entity = requireInventory(inventoryId);
        // 类型与关联物品创建后不可改：要换物品请删除后重新添加，避免"换了物品却留着旧数量"
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<CharacterInventoryEntity> update =
                Wrappers.<CharacterInventoryEntity>lambdaUpdate()
                        .eq(CharacterInventoryEntity::getId, inventoryId);
        boolean dirty = false;

        if (entity.getItemId() == null && hasText(request.getName())) {
            String name = request.getName().trim();
            if (!name.equals(entity.getName())) {
                Long exists = inventoryMapper.selectCount(Wrappers.<CharacterInventoryEntity>lambdaQuery()
                        .eq(CharacterInventoryEntity::getCharacterId, entity.getCharacterId())
                        .isNull(CharacterInventoryEntity::getItemId)
                        .eq(CharacterInventoryEntity::getName, name)
                        .ne(CharacterInventoryEntity::getId, inventoryId));
                if (exists != null && exists > 0) {
                    throw BizException.of(ErrorCode.INVENTORY_EXISTS, "「" + name + "」已经在背包里了");
                }
                entity.setName(name);
                update.set(CharacterInventoryEntity::getName, name);
                dirty = true;
            }
        }
        if (Boolean.TRUE.equals(request.getClearQuantity())) {
            entity.setQuantity(null);
            // 数值型字段 updateById 会跳过 null，必须用 set(field, null) 才能清空
            update.set(CharacterInventoryEntity::getQuantity, null);
            dirty = true;
        } else if (request.getQuantity() != null) {
            entity.setQuantity(request.getQuantity());
            update.set(CharacterInventoryEntity::getQuantity, request.getQuantity());
            dirty = true;
        }
        if (request.getUnit() != null) {
            String unit = request.getUnit().trim();
            entity.setUnit(unit);
            update.set(CharacterInventoryEntity::getUnit, unit);
            dirty = true;
        }
        if (hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
            update.set(CharacterInventoryEntity::getStatus, request.getStatus());
            dirty = true;
        }
        if (request.getNote() != null) {
            String note = request.getNote().trim();
            entity.setNote(note);
            update.set(CharacterInventoryEntity::getNote, note);
            dirty = true;
        }
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
            update.set(CharacterInventoryEntity::getSortNo, request.getSortNo());
            dirty = true;
        }
        // 一次 update 写完，避免同一行两次 update 触发乐观锁失败
        if (dirty) {
            inventoryMapper.update(null, update);
        }
        ItemEntity item = entity.getItemId() == null ? null : itemMapper.selectById(entity.getItemId());
        return toInventoryVO(entity, item);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteInventory(Long inventoryId) {
        requireInventory(inventoryId);
        inventoryMapper.deleteById(inventoryId);
    }

    /* ------------------------------------------------------------------ */
    /* 生成提示词用                                                       */
    /* ------------------------------------------------------------------ */

    @Override
    public Map<Long, List<AttributeVO>> attributesByCharacter(Long novelId) {
        Map<Long, List<AttributeVO>> map = new LinkedHashMap<>();
        for (CharacterAttributeEntity row : attributeMapper.selectList(
                Wrappers.<CharacterAttributeEntity>lambdaQuery()
                        .eq(CharacterAttributeEntity::getNovelId, novelId)
                        .orderByAsc(CharacterAttributeEntity::getSortNo)
                        .orderByAsc(CharacterAttributeEntity::getId))) {
            map.computeIfAbsent(row.getCharacterId(), key -> new ArrayList<>()).add(toAttributeVO(row));
        }
        return map;
    }

    @Override
    public Map<Long, List<InventoryVO>> inventoryByCharacter(Long novelId) {
        List<CharacterInventoryEntity> rows = inventoryMapper.selectList(
                Wrappers.<CharacterInventoryEntity>lambdaQuery()
                        .eq(CharacterInventoryEntity::getNovelId, novelId)
                        .orderByAsc(CharacterInventoryEntity::getSortNo)
                        .orderByAsc(CharacterInventoryEntity::getId));
        Map<Long, ItemEntity> items = itemMap(rows);
        Map<Long, List<InventoryVO>> map = new LinkedHashMap<>();
        for (CharacterInventoryEntity row : rows) {
            map.computeIfAbsent(row.getCharacterId(), key -> new ArrayList<>())
                    .add(toInventoryVO(row, items.get(row.getItemId())));
        }
        return map;
    }

    @Override
    public List<CharacterAttributeEntity> attributeEntities(Long novelId) {
        return attributeMapper.selectList(Wrappers.<CharacterAttributeEntity>lambdaQuery()
                .eq(CharacterAttributeEntity::getNovelId, novelId)
                .orderByAsc(CharacterAttributeEntity::getCharacterId)
                .orderByAsc(CharacterAttributeEntity::getSortNo));
    }

    @Override
    public List<CharacterInventoryEntity> inventoryEntities(Long novelId) {
        return inventoryMapper.selectList(Wrappers.<CharacterInventoryEntity>lambdaQuery()
                .eq(CharacterInventoryEntity::getNovelId, novelId)
                .orderByAsc(CharacterInventoryEntity::getCharacterId)
                .orderByAsc(CharacterInventoryEntity::getSortNo));
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                           */
    /* ------------------------------------------------------------------ */

    private CharacterEntity requireCharacter(Long characterId) {
        CharacterEntity entity = characterId == null ? null : characterMapper.selectById(characterId);
        if (entity == null) {
            throw BizException.of(ErrorCode.CHARACTER_NOT_FOUND);
        }
        return entity;
    }

    private CharacterAttributeEntity requireAttribute(Long attributeId) {
        CharacterAttributeEntity entity = attributeId == null ? null : attributeMapper.selectById(attributeId);
        if (entity == null) {
            throw BizException.of(ErrorCode.ATTRIBUTE_NOT_FOUND);
        }
        return entity;
    }

    private CharacterInventoryEntity requireInventory(Long inventoryId) {
        CharacterInventoryEntity entity = inventoryId == null ? null : inventoryMapper.selectById(inventoryId);
        if (entity == null) {
            throw BizException.of(ErrorCode.INVENTORY_NOT_FOUND);
        }
        return entity;
    }

    private ItemEntity requireItemOfNovel(Long itemId, Long novelId) {
        ItemEntity item = itemId == null ? null : itemMapper.selectById(itemId);
        if (item == null || !novelId.equals(item.getNovelId())) {
            throw BizException.of(ErrorCode.INVENTORY_ITEM_INVALID, "关联的物品不属于本书");
        }
        return item;
    }

    private Map<Long, ItemEntity> itemMap(List<CharacterInventoryEntity> rows) {
        Map<Long, ItemEntity> map = new LinkedHashMap<>();
        for (CharacterInventoryEntity row : rows) {
            if (row.getItemId() == null || map.containsKey(row.getItemId())) {
                continue;
            }
            ItemEntity item = itemMapper.selectById(row.getItemId());
            if (item != null) {
                map.put(item.getId(), item);
            }
        }
        return map;
    }

    private AttributeVO toAttributeVO(CharacterAttributeEntity entity) {
        return new AttributeVO(
                entity.getId(),
                entity.getCharacterId(),
                entity.getName(),
                entity.getValue(),
                entity.getUnit(),
                entity.getAttrType(),
                entity.getNote(),
                entity.getSortNo());
    }

    private InventoryVO toInventoryVO(CharacterInventoryEntity entity, ItemEntity item) {
        String entryType = hasText(entity.getEntryType()) ? entity.getEntryType() : "MISC";
        String status = hasText(entity.getStatus()) ? entity.getStatus() : "HELD";
        return new InventoryVO(
                entity.getId(),
                entity.getCharacterId(),
                entryType,
                ENTRY_LABELS.getOrDefault(entryType, entryType),
                entity.getItemId(),
                entity.getName(),
                entity.getQuantity(),
                entity.getUnit(),
                status,
                STATUS_LABELS.getOrDefault(status, status),
                entity.getNote(),
                item == null ? null : item.getDescription(),
                item == null ? null : item.getOrigin(),
                entity.getSortNo());
    }

    private int nextAttributeSort(Long characterId) {
        return attributeMapper.selectList(Wrappers.<CharacterAttributeEntity>lambdaQuery()
                        .eq(CharacterAttributeEntity::getCharacterId, characterId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    private int nextInventorySort(Long characterId) {
        return inventoryMapper.selectList(Wrappers.<CharacterInventoryEntity>lambdaQuery()
                        .eq(CharacterInventoryEntity::getCharacterId, characterId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
