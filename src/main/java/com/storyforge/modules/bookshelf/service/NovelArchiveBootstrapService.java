package com.storyforge.modules.bookshelf.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.storyforge.modules.bookshelf.dto.CharacterSaveRequest;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.FactionRequest;
import com.storyforge.modules.bookshelf.dto.FactionVO;
import com.storyforge.modules.bookshelf.dto.ItemRequest;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 生成阶段的「初始化档案」。
 *
 * <p>AI 写大纲、写正文时常常先写出一个档案里还没有的人 / 道具 / 组织：以前只能等
 * 章节分析时再补建，作者看到的档案总是落后于剧情。这里把「按模型给的 JSON 建档案」
 * 抽成公共能力，大纲与正文两处生成都能调用，规则保持一致：</p>
 *
 * <ul>
 *   <li>同名（忽略引号空白）不重复建，直接复用档案里已有的记录；</li>
 *   <li>单次补建有上限，避免模型一次造出一整个人物表；</li>
 *   <li>主角已存在时，新角色不再抢主线视角（降级为配角）；</li>
 *   <li>枚举字段宽松归一，非法值退默认，绝不因为一个字段把整次生成废掉。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NovelArchiveBootstrapService {

    /** 单次生成最多补建多少：既防模型把档案一次撑爆，也防它凭空造一堆人和道具。 */
    public static final int MAX_CHARACTERS = 6;
    public static final int MAX_ITEMS = 5;
    public static final int MAX_FACTIONS = 3;

    private final NovelAssetService assetService;
    private final FactionService factionService;

    /* ------------------------------------------------------------------ */
    /* 人物                                                               */
    /* ------------------------------------------------------------------ */

    public List<CharacterVO> createCharacters(Long novelId,
                                              JsonNode nodes,
                                              Map<String, Long> knownIds,
                                              boolean hasProtagonist) {
        return createCharacters(novelId, nodes, knownIds, hasProtagonist, MAX_CHARACTERS);
    }

    /**
     * 补建人物。已有的同名角色直接跳过（复用真实 id），绝不覆盖作者写好的档案。
     *
     * @param knownIds       现有「姓名 → id」映射，本方法会把新建的角色追加进去
     * @param hasProtagonist 档案里是否已有主角
     */
    public List<CharacterVO> createCharacters(Long novelId,
                                              JsonNode nodes,
                                              Map<String, Long> knownIds,
                                              boolean hasProtagonist,
                                              int max) {
        List<CharacterVO> created = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            return created;
        }
        Set<String> knownNames = new LinkedHashSet<>();
        knownIds.keySet().forEach(name -> knownNames.add(key(name)));
        boolean protagonistTaken = hasProtagonist;
        for (JsonNode node : nodes) {
            if (created.size() >= max) {
                break;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            String name = truncate(textOf(node, "name"), 64);
            if (name == null || knownNames.contains(key(name))) {
                continue;
            }
            String rolePos = normalizeRole(textOf(node, "rolePos"));
            // 已有主角时不再让新角色抢主线视角
            if ("PROTAGONIST".equals(rolePos)) {
                if (protagonistTaken) {
                    rolePos = "SUPPORTING";
                } else {
                    protagonistTaken = true;
                }
            }
            CharacterSaveRequest save = new CharacterSaveRequest();
            save.setName(name);
            save.setRolePos(rolePos);
            save.setIdentity(truncate(textOf(node, "identity"), 255));
            save.setGender(truncate(textOf(node, "gender"), 16));
            save.setAgeDesc(truncate(textOf(node, "ageDesc"), 32));
            save.setAppearance(truncate(textOf(node, "appearance"), 2000));
            save.setPersonality(truncate(textOf(node, "personality"), 2000));
            save.setMotivation(truncate(textOf(node, "motivation"), 2000));
            save.setGoldenFinger(truncate(textOf(node, "goldenFinger"), 2000));
            save.setArc(truncate(textOf(node, "arc"), 2000));
            CharacterVO vo = assetService.createCharacter(novelId, save);
            knownIds.put(name, vo.getId());
            knownNames.add(key(name));
            created.add(vo);
        }
        return created;
    }

    /* ------------------------------------------------------------------ */
    /* 物品 / 势力                                                        */
    /* ------------------------------------------------------------------ */

    public List<ItemVO> createItems(Long novelId, JsonNode nodes, Set<String> existingNames) {
        return createItems(novelId, nodes, existingNames, MAX_ITEMS);
    }

    public List<ItemVO> createItems(Long novelId, JsonNode nodes, Set<String> existingNames, int max) {
        List<ItemVO> created = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            return created;
        }
        Set<String> names = keysOf(existingNames);
        for (JsonNode node : nodes) {
            if (created.size() >= max) {
                break;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            String name = truncate(textOf(node, "name"), 100);
            if (name == null || !names.add(key(name))) {
                continue;
            }
            ItemRequest save = new ItemRequest();
            save.setName(name);
            save.setCategory(truncate(textOf(node, "category"), 32));
            save.setOwner(truncate(textOf(node, "owner"), 64));
            save.setDescription(truncate(textOf(node, "description"), 2000));
            save.setOrigin(truncate(textOf(node, "origin"), 2000));
            save.setStatus(normalizeItemStatus(textOf(node, "status")));
            created.add(assetService.createItem(novelId, save));
        }
        return created;
    }

    public List<FactionVO> createFactions(Long novelId, JsonNode nodes, Set<String> existingNames) {
        return createFactions(novelId, nodes, existingNames, MAX_FACTIONS);
    }

    public List<FactionVO> createFactions(Long novelId, JsonNode nodes, Set<String> existingNames, int max) {
        List<FactionVO> created = new ArrayList<>();
        if (nodes == null || !nodes.isArray()) {
            return created;
        }
        Set<String> names = keysOf(existingNames);
        for (JsonNode node : nodes) {
            if (created.size() >= max) {
                break;
            }
            if (node == null || !node.isObject()) {
                continue;
            }
            String name = truncate(textOf(node, "name"), 100);
            if (name == null || !names.add(key(name))) {
                continue;
            }
            FactionRequest save = new FactionRequest();
            save.setName(name);
            save.setCategory(truncate(textOf(node, "category"), 32));
            save.setScope(truncate(textOf(node, "scope"), 255));
            save.setRemark(truncate(textOf(node, "remark"), 2000));
            save.setStatus(normalizeFactionStatus(textOf(node, "status")));
            created.add(factionService.create(novelId, save));
        }
        return created;
    }

    /* ------------------------------------------------------------------ */
    /* 枚举归一（接受英文码或中文标签，非法值退默认）                     */
    /* ------------------------------------------------------------------ */

    public static String normalizeRole(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "SUPPORTING";
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "PROTAGONIST", "SUPPORTING", "ANTAGONIST", "MINOR" -> upper;
            default -> switch (value) {
                case "主角" -> "PROTAGONIST";
                case "反派", "对手" -> "ANTAGONIST";
                case "龙套", "路人" -> "MINOR";
                default -> "SUPPORTING";
            };
        };
    }

    public static String normalizeItemStatus(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "ACTIVE";
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "ACTIVE", "DESTROYED", "ABANDONED", "LOST", "CONSUMED" -> upper;
            default -> switch (value) {
                case "摧毁", "损毁" -> "DESTROYED";
                case "废弃" -> "ABANDONED";
                case "遗失", "丢失" -> "LOST";
                case "已消耗", "耗尽" -> "CONSUMED";
                default -> "ACTIVE";
            };
        };
    }

    public static String normalizeFactionStatus(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "ACTIVE";
        }
        String upper = value.toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "ACTIVE", "DORMANT", "DESTROYED", "DISBANDED" -> upper;
            default -> switch (value) {
                case "蛰伏", "潜伏" -> "DORMANT";
                case "覆灭" -> "DESTROYED";
                case "解散" -> "DISBANDED";
                default -> "ACTIVE";
            };
        };
    }

    /* ------------------------------------------------------------------ */
    /* 工具                                                               */
    /* ------------------------------------------------------------------ */

    private Set<String> keysOf(Set<String> names) {
        Set<String> keys = new LinkedHashSet<>();
        if (names != null) {
            names.forEach(name -> keys.add(key(name)));
        }
        return keys;
    }

    /** 姓名比对键：去掉空白与各种引号包裹，容忍模型多写的标点。 */
    static String key(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\s「」『』《》\"'·]", "").trim().toLowerCase(Locale.ROOT);
    }

    static String textOf(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.path(field);
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.asText("").trim();
        return text.isEmpty() ? null : text;
    }

    static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
