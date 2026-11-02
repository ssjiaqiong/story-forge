package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.ForeshadowEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 伏笔台账 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface ForeshadowMapper extends BaseMapper<ForeshadowEntity> {
}
