package com.storyforge.modules.novel.service;

import com.storyforge.modules.novel.dto.CoverGenerateRequest;
import com.storyforge.modules.novel.dto.NovelCoverVO;

import java.util.List;
import java.util.Map;

/**
 * 小说封面服务（按用户隔离）。
 */
public interface NovelCoverService {

    /** 一部小说的全部封面：默认展示的那张排在最前，其余按切换顺序。 */
    List<NovelCoverVO> list(Long novelId);

    /** 调文生图接口生成一张封面并落盘；超过 5 张时拒绝。 */
    NovelCoverVO generate(Long novelId, CoverGenerateRequest request);

    /** 设为书架默认展示的封面。 */
    NovelCoverVO setPrimary(Long coverId);

    /** 删除封面（同时删除图片文件）。 */
    void delete(Long coverId);

    /** 删除一部小说的全部封面与图片目录（删小说时调用）。 */
    void deleteAllForNovel(Long userId, Long novelId);

    /** 读取封面图片，供鉴权后的取图接口使用。 */
    CoverImage loadImage(Long coverId);

    /** 书架列表批量取封面：一次查询，按小说分组。 */
    Map<Long, List<NovelCoverVO>> groupByNovel(List<Long> novelIds);

    /** 封面图片字节。 */
    record CoverImage(byte[] data, String mimeType) {
    }
}
