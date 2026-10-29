package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.NovelImportEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 整本 TXT 导入任务 Mapper。
 */
@Mapper
public interface NovelImportMapper extends BaseMapper<NovelImportEntity> {
}
