package com.storyforge.infra.search;

import java.util.List;

/**
 * 搜索源实现。
 */
public interface SearchProviderClient {

    /** 返回按相关度排好序的结果，长度不超过 {@code endpoint.topK()}。 */
    List<SearchHit> search(SearchEndpoint endpoint, String query);
}
