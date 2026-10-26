package com.storyforge.modules.bookshelf.service;

import com.storyforge.modules.bookshelf.dto.ChapterRequest;
import com.storyforge.modules.bookshelf.dto.ChapterVO;
import com.storyforge.modules.bookshelf.dto.CharacterRelationVO;
import com.storyforge.modules.bookshelf.dto.CharacterSaveRequest;
import com.storyforge.modules.bookshelf.dto.CharacterTreeNode;
import com.storyforge.modules.bookshelf.dto.CharacterVO;
import com.storyforge.modules.bookshelf.dto.ItemRequest;
import com.storyforge.modules.bookshelf.dto.ItemVO;
import com.storyforge.modules.bookshelf.dto.RelationRequest;
import com.storyforge.modules.bookshelf.dto.TimelineRequest;
import com.storyforge.modules.bookshelf.dto.TimelineVO;

import java.util.List;

/**
 * 小说工作台要素服务：章节（大纲 / 正文）、时间线、物品、角色。
 *
 * <p>所有方法都以小说归属校验为前置条件，越权访问统一抛 {@code 40016}。</p>
 */
public interface NovelAssetService {

    List<ChapterVO> listChapters(Long novelId);

    ChapterVO getChapter(Long chapterId);

    ChapterVO createChapter(Long novelId, ChapterRequest request);

    ChapterVO updateChapter(Long chapterId, ChapterRequest request);

    void deleteChapter(Long chapterId);

    List<TimelineVO> listTimeline(Long novelId);

    TimelineVO createTimeline(Long novelId, TimelineRequest request);

    TimelineVO updateTimeline(Long eventId, TimelineRequest request);

    void deleteTimeline(Long eventId);

    List<ItemVO> listItems(Long novelId);

    ItemVO createItem(Long novelId, ItemRequest request);

    ItemVO updateItem(Long itemId, ItemRequest request);

    void deleteItem(Long itemId);

    List<CharacterVO> listCharacters(Long novelId);

    CharacterVO createCharacter(Long novelId, CharacterSaveRequest request);

    CharacterVO updateCharacter(Long characterId, CharacterSaveRequest request);

    void deleteCharacter(Long characterId);

    /** 关系列表（对称，两端已归一化）。 */
    List<CharacterRelationVO> listRelations(Long novelId);

    CharacterRelationVO createRelation(Long novelId, RelationRequest request);

    CharacterRelationVO updateRelation(Long relationId, RelationRequest request);

    void deleteRelation(Long relationId);

    /** 以主角为根的人物关系森林；孤立角色也会作为单节点树返回。 */
    List<CharacterTreeNode> relationTree(Long novelId);
}
