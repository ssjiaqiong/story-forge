package com.storyforge.modules.search.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.storyforge.modules.search.entity.SearchConfigEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SearchConfigMapper extends BaseMapper<SearchConfigEntity> {

    /** 统计包含已删除记录的总数：用于判断「用户是否从未配置过」，避免删空后反复重建默认源。 */
    @Select("select count(*) from t_search_config where user_id = #{userId}")
    long countAllByUser(@Param("userId") Long userId);

    /** 按名称统计未删除记录：初始化默认源时去重，避免撞 uk_search_config_name。 */
    @Select("select count(*) from t_search_config where user_id = #{userId} and name = #{name} and deleted = 0")
    long countByUserName(@Param("userId") Long userId, @Param("name") String name);
}
