package com.storyforge.modules.search.service;

import com.storyforge.modules.ai.scene.AiScene;
import com.storyforge.modules.search.dto.SearchBundleVO;

/**
 * 联网检索统一入口。
 *
 * <p>按场景绑定 → 默认搜索源解析配置，失败不影响主流程（返回带 error 的结果，
 * 调用方按「无资料」继续生成）。同一查询 10 分钟内命中内存缓存。</p>
 */
public interface WebSearchService {

    /**
     * @param scene 场景编码，决定用哪个绑定的搜索源
     * @param query 检索词
     * @param topK  覆盖条数，为空时取场景 / 搜索源默认值
     */
    SearchBundleVO search(AiScene scene, String query, Integer topK);

    /** 指定搜索源检索（设置页试搜用）。 */
    SearchBundleVO searchWith(Long configId, String query, Integer topK);
}
