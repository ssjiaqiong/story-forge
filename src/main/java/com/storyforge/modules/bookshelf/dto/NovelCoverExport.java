package com.storyforge.modules.bookshelf.dto;

import lombok.Data;

/**
 * 封面导出条目：表字段 + 图片本体（Base64）。
 *
 * <p>导出时把封面文件读成 Base64 内嵌进 JSON，保证「一个文件搬走整本书」；
 * 导入时重新落盘并以新用户/新小说目录重建 {@code file_path}，不使用这里的历史路径。</p>
 */
@Data
public class NovelCoverExport {

    /** 旧封面 id（仅导出侧标识，导入不使用） */
    private Long id;

    /** 实际图片类型：image/jpeg / image/png / image/webp */
    private String mimeType;

    private Integer fileSize;

    private Integer width;

    private Integer height;

    private String prompt;

    private String negativePrompt;

    private String style;

    private Integer hasText;

    private String titleText;

    private String authorText;

    private String textMode;

    private String provider;

    private String model;

    /** 1 = 主封面 */
    private Integer isPrimary;

    private Integer sortNo;

    /** 图片字节的 Base64；原文件缺失时为 null */
    private String imageData;
}
