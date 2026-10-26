package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.FactionMemberEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 势力成员 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface FactionMemberMapper extends BaseMapper<FactionMemberEntity> {
}
