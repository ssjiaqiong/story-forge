package com.storyforge.modules.bookshelf.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.storyforge.common.api.ErrorCode;
import com.storyforge.common.context.UserContextHolder;
import com.storyforge.common.exception.BizException;
import com.storyforge.modules.bookshelf.dto.FactionMemberRequest;
import com.storyforge.modules.bookshelf.dto.FactionMemberVO;
import com.storyforge.modules.bookshelf.dto.FactionRequest;
import com.storyforge.modules.bookshelf.dto.FactionVO;
import com.storyforge.modules.bookshelf.entity.CharacterEntity;
import com.storyforge.modules.bookshelf.entity.FactionEntity;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import com.storyforge.modules.bookshelf.mapper.CharacterMapper;
import com.storyforge.modules.bookshelf.mapper.ChangeHistoryMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMapper;
import com.storyforge.modules.bookshelf.mapper.FactionMemberMapper;
import com.storyforge.modules.novel.entity.NovelEntity;
import com.storyforge.modules.novel.mapper.NovelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 势力服务实现。
 *
 * <p>一个角色可以在多个势力任职：成员表按 (faction_id, character_id) 唯一，
 * 不同势力各占一行，职位是各行独立的自由文本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FactionServiceImpl implements FactionService {

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "ACTIVE", "活跃",
            "DORMANT", "蛰伏",
            "DESTROYED", "覆灭",
            "DISBANDED", "解散");

    private final FactionMapper factionMapper;
    private final FactionMemberMapper factionMemberMapper;
    private final ChangeHistoryMapper changeHistoryMapper;
    private final CharacterMapper characterMapper;
    private final NovelMapper novelMapper;

    @Override
    public List<FactionVO> list(Long novelId) {
        requireNovel(novelId);
        Map<Long, CharacterEntity> characters = characterMap(novelId);
        Map<Long, List<FactionMemberVO>> members = memberMap(novelId, characters);
        return factionMapper.selectList(Wrappers.<FactionEntity>lambdaQuery()
                        .eq(FactionEntity::getNovelId, novelId)
                        .orderByAsc(FactionEntity::getSortNo)
                        .orderByAsc(FactionEntity::getId))
                .stream()
                .map(entity -> toVO(entity, members.getOrDefault(entity.getId(), List.of())))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FactionVO create(Long novelId, FactionRequest request) {
        requireNovel(novelId);
        FactionEntity entity = new FactionEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(novelId);
        entity.setName(request.getName().trim());
        entity.setCategory(trimToNull(request.getCategory()));
        entity.setRemark(trimToNull(request.getRemark()));
        entity.setScope(trimToNull(request.getScope()));
        entity.setStatus(hasText(request.getStatus()) ? request.getStatus() : "ACTIVE");
        entity.setSortNo(request.getSortNo() == null ? nextSortNo(novelId) : request.getSortNo());
        entity.setDeleted(0);
        entity.setVersion(0);
        factionMapper.insert(entity);
        log.info("[faction] created novelId={} id={} name={}", novelId, entity.getId(), entity.getName());
        return toVO(entity, List.of());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FactionVO update(Long factionId, FactionRequest request) {
        FactionEntity entity = requireFaction(factionId);
        if (request.getName() != null) {
            if (!hasText(request.getName())) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "势力名称不能为空");
            }
            entity.setName(request.getName().trim());
        }
        // 空串而非 null：updateById 会跳过 null，"清空备注/范围"会失效
        if (request.getCategory() != null) {
            entity.setCategory(request.getCategory().trim());
        }
        if (request.getRemark() != null) {
            entity.setRemark(request.getRemark().trim());
        }
        if (request.getScope() != null) {
            entity.setScope(request.getScope().trim());
        }
        if (hasText(request.getStatus())) {
            entity.setStatus(request.getStatus());
        }
        if (request.getSortNo() != null) {
            entity.setSortNo(request.getSortNo());
        }
        factionMapper.updateById(entity);
        Map<Long, CharacterEntity> characters = characterMap(entity.getNovelId());
        return toVO(entity, memberMap(entity.getNovelId(), characters)
                .getOrDefault(entity.getId(), List.of()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long factionId) {
        FactionEntity entity = requireFaction(factionId);
        factionMemberMapper.delete(Wrappers.<FactionMemberEntity>lambdaQuery()
                .eq(FactionMemberEntity::getFactionId, factionId));
        changeHistoryMapper.delete(Wrappers.<com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity>lambdaQuery()
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getEntityType, "FACTION")
                .eq(com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity::getEntityId, factionId));
        factionMapper.deleteById(factionId);
        log.info("[faction] deleted id={} name={}", factionId, entity.getName());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FactionMemberVO addMember(Long factionId, FactionMemberRequest request) {
        FactionEntity faction = requireFaction(factionId);
        CharacterEntity character = characterMapper.selectById(request.getCharacterId());
        if (character == null || !faction.getNovelId().equals(character.getNovelId())) {
            throw BizException.of(ErrorCode.CHARACTER_NOT_FOUND, "角色必须属于本书");
        }
        Long exists = factionMemberMapper.selectCount(Wrappers.<FactionMemberEntity>lambdaQuery()
                .eq(FactionMemberEntity::getFactionId, factionId)
                .eq(FactionMemberEntity::getCharacterId, request.getCharacterId()));
        if (exists != null && exists > 0) {
            throw BizException.of(ErrorCode.FACTION_MEMBER_EXISTS);
        }

        FactionMemberEntity entity = new FactionMemberEntity();
        entity.setUserId(UserContextHolder.requireUserId());
        entity.setNovelId(faction.getNovelId());
        entity.setFactionId(factionId);
        entity.setCharacterId(request.getCharacterId());
        entity.setPosition(request.getPosition().trim());
        entity.setSortNo(nextMemberSort(factionId));
        entity.setDeleted(0);
        entity.setVersion(0);
        factionMemberMapper.insert(entity);
        return toMemberVO(entity, character);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public FactionMemberVO updateMember(Long memberId, String position) {
        FactionMemberEntity entity = requireMember(memberId);
        if (!hasText(position)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "请填写该角色的职位");
        }
        entity.setPosition(position.trim());
        factionMemberMapper.updateById(entity);
        CharacterEntity character = characterMapper.selectById(entity.getCharacterId());
        return toMemberVO(entity, character);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeMember(Long memberId) {
        requireMember(memberId);
        factionMemberMapper.deleteById(memberId);
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                           */
    /* ------------------------------------------------------------------ */

    private NovelEntity requireNovel(Long novelId) {
        NovelEntity novel = novelId == null ? null : novelMapper.selectById(novelId);
        if (novel == null) {
            throw BizException.of(ErrorCode.NOVEL_NOT_FOUND);
        }
        return novel;
    }

    private FactionEntity requireFaction(Long factionId) {
        FactionEntity entity = factionId == null ? null : factionMapper.selectById(factionId);
        if (entity == null) {
            throw BizException.of(ErrorCode.FACTION_NOT_FOUND);
        }
        return entity;
    }

    private FactionMemberEntity requireMember(Long memberId) {
        FactionMemberEntity entity = memberId == null ? null : factionMemberMapper.selectById(memberId);
        if (entity == null) {
            throw BizException.of(ErrorCode.FACTION_MEMBER_NOT_FOUND);
        }
        return entity;
    }

    private Map<Long, CharacterEntity> characterMap(Long novelId) {
        Map<Long, CharacterEntity> map = new LinkedHashMap<>();
        for (CharacterEntity item : characterMapper.selectList(Wrappers.<CharacterEntity>lambdaQuery()
                .eq(CharacterEntity::getNovelId, novelId))) {
            map.put(item.getId(), item);
        }
        return map;
    }

    private Map<Long, List<FactionMemberVO>> memberMap(Long novelId, Map<Long, CharacterEntity> characters) {
        Map<Long, List<FactionMemberVO>> map = new LinkedHashMap<>();
        for (FactionMemberEntity row : factionMemberMapper.selectList(
                Wrappers.<FactionMemberEntity>lambdaQuery()
                        .eq(FactionMemberEntity::getNovelId, novelId)
                        .orderByAsc(FactionMemberEntity::getSortNo)
                        .orderByAsc(FactionMemberEntity::getId))) {
            CharacterEntity character = characters.get(row.getCharacterId());
            if (character == null) {
                continue;
            }
            map.computeIfAbsent(row.getFactionId(), key -> new ArrayList<>())
                    .add(toMemberVO(row, character));
        }
        return map;
    }

    private FactionMemberVO toMemberVO(FactionMemberEntity entity, CharacterEntity character) {
        return new FactionMemberVO(
                entity.getId(),
                entity.getCharacterId(),
                character == null ? "未知角色" : character.getName(),
                character == null ? "角色" : roleLabel(character.getRolePos()),
                entity.getPosition());
    }

    private FactionVO toVO(FactionEntity entity, List<FactionMemberVO> members) {
        String status = hasText(entity.getStatus()) ? entity.getStatus() : "ACTIVE";
        return new FactionVO(
                entity.getId(),
                entity.getName(),
                entity.getCategory(),
                entity.getRemark(),
                entity.getScope(),
                status,
                STATUS_LABELS.getOrDefault(status, status),
                members.size(),
                members,
                entity.getUpdatedAt());
    }

    private int nextSortNo(Long novelId) {
        return factionMapper.selectList(Wrappers.<FactionEntity>lambdaQuery()
                        .eq(FactionEntity::getNovelId, novelId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    private int nextMemberSort(Long factionId) {
        return factionMemberMapper.selectList(Wrappers.<FactionMemberEntity>lambdaQuery()
                        .eq(FactionMemberEntity::getFactionId, factionId))
                .stream()
                .mapToInt(item -> item.getSortNo() == null ? 0 : item.getSortNo())
                .max()
                .orElse(0) + 1;
    }

    private String roleLabel(String rolePos) {
        if (rolePos == null) {
            return "角色";
        }
        return switch (rolePos) {
            case "PROTAGONIST" -> "主角";
            case "ANTAGONIST" -> "反派";
            case "MINOR" -> "龙套";
            default -> "配角";
        };
    }

    private String trimToNull(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
