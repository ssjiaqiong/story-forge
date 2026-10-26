package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.ChangeHistoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 要素变更履历 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface ChangeHistoryMapper extends BaseMapper<ChangeHistoryEntity> {
}
