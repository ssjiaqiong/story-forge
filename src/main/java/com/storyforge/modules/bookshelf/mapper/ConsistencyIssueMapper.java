package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.ConsistencyIssueEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 一致性问题 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface ConsistencyIssueMapper extends BaseMapper<ConsistencyIssueEntity> {
}
