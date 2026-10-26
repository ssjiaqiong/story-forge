package com.storyforge.common.api;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果。
 *
 * @param <T> 记录类型
 */
@Data
public class PageResult<T> implements Serializable {

    /** 当前页记录 */
    private List<T> records = Collections.emptyList();
    /** 总记录数 */
    private long total;
    /** 当前页码，从 1 开始 */
    private long page = 1;
    /** 每页条数 */
    private long size = 20;
    /** 总页数 */
    private long pages;

    public static <T> PageResult<T> of(List<T> records, long total, long page, long size) {
        PageResult<T> result = new PageResult<>();
        result.records = records == null ? Collections.emptyList() : records;
        result.total = total;
        result.page = page;
        result.size = size <= 0 ? 20 : size;
        result.pages = result.size == 0 ? 0 : (total + result.size - 1) / result.size;
        return result;
    }
}
