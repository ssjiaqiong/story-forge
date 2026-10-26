package com.storyforge.modules.search.service;

import com.storyforge.modules.search.dto.SearchConfigRequest;
import com.storyforge.modules.search.dto.SearchConfigVO;
import com.storyforge.modules.search.dto.SearchTestRequest;
import com.storyforge.modules.search.dto.SearchTestResultVO;

import java.util.List;

/**
 * 联网搜索源配置。
 */
public interface SearchConfigService {

    /** 列出当前用户的搜索源；首次进入自动初始化免密钥默认源。 */
    List<SearchConfigVO> list();

    /** 详情：额外解密返回明文 API Key，供编辑页回填。 */
    SearchConfigVO detail(Long id);

    SearchConfigVO create(SearchConfigRequest request);

    SearchConfigVO update(Long id, SearchConfigRequest request);

    void delete(Long id);

    SearchConfigVO setDefault(Long id);

    SearchConfigVO toggle(Long id, boolean enabled);

    /** 测试已保存的搜索源。 */
    SearchTestResultVO test(Long id, SearchTestRequest request);

    /** 测试表单内容（可复用已保存的密钥）。 */
    SearchTestResultVO probe(SearchTestRequest request);

    /** 没有历史记录时写入默认可用的免密钥搜索源。 */
    void ensureDefaults();
}
