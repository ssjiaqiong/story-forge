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

    /**
     * 带来源的章节更新。
     *
     * <p>{@code source} 只影响历史版本上的来源标签（手动保存 / AI 写正文 / 批量连写 …），
     * 由调用方在内部传入，不接受前端指定。</p>
     */
    ChapterVO updateChapter(Long chapterId, ChapterRequest request, String source);

    /**
     * 正文自动保存（轻量路径）。
     *
     * <p>只改本章正文与字数，并<b>增量</b>修正小说的总字数——不走
     * {@link #updateChapter(Long, ChapterRequest)}：那条路会重算全书字数、重建关联、
     * 每次都存快照，20 秒一次的自动保存用不起。快照在这里按「距上一版多久」节流，
     * 来源记为 {@code AUTO}。</p>
     *
     * <p>{@code version} 是客户端上次读到的乐观锁版本号：与库里不一致说明别处已改过，
     * 抛 {@code 40071}（前端据此弹「用我的覆盖 / 放弃我的改动」）。传 null 表示不做冲突检测。</p>
     */
    ChapterVO saveContent(Long chapterId, String content, Integer version);

    /**
     * 把章节移动到另一卷。
     *
     * <p>只改归属，不动章号；目标卷不存在时自动建一卷。改卷名的唯一入口是
     * {@code VolumeService}，这里只是把章节的冗余字段对齐过去。</p>
     */
    ChapterVO moveChapterToVolume(Long chapterId, Integer volumeNo);

    /**
     * 回滚到某个历史版本。
     *
     * <p>回滚前的当前稿会先存为新版本，所以可以再回滚回来；已标记「已完成」的章节也允许回滚
     * （锁定保护只针对外部编辑入口，见 {@link #updateChapter(Long, ChapterRequest)}）。</p>
     */
    ChapterVO restoreVersion(Long chapterId, Long versionId);

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
