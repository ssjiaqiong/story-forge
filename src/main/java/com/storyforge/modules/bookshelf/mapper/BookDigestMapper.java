package com.storyforge.modules.bookshelf.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.bookshelf.entity.BookDigestEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 拆书记录 Mapper。
 */
@Mapper
public interface BookDigestMapper extends BaseMapper<BookDigestEntity> {
}
