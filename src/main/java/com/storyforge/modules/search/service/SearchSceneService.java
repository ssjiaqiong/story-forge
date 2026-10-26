package com.storyforge.modules.search.service;

import com.storyforge.modules.search.dto.SearchSceneSaveRequest;
import com.storyforge.modules.search.dto.SearchSceneVO;

import java.util.List;

/**
 * 搜索场景绑定：事件 / 大纲 / 正文三个生成场景分别使用哪个搜索源。
 */
public interface SearchSceneService {

    List<SearchSceneVO> list();

    void save(SearchSceneSaveRequest request);
}
