package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.SettingDocEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 小说设定 Mapper，隔离条件由数据权限插件自动追加。
 */
@Mapper
public interface SettingDocMapper extends BaseMapper<SettingDocEntity> {
}
