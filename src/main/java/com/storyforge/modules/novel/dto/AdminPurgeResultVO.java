package com.storyforge.modules.novel.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 单本小说物理删除结果：便于运营确认「究竟清掉了多少关联数据」。
 */
@Data
@Schema(description = "小说物理删除结果")
public class AdminPurgeResultVO {

    @Schema(description = "小说 ID")
    private Long novelId;

    @Schema(description = "书名")
    private String title;

    @Schema(description = "创建用户登录名")
    private String username;

    @Schema(description = "是否清除成功")
    private boolean success = true;

    @Schema(description = "失败原因（success=false 时有值）")
    private String message;

    @Schema(description = "各关联表删除行数，键为表名")
    private Map<String, Integer> tableRows = new LinkedHashMap<>();

    @Schema(description = "累计删除行数（含小说主表）")
    private int totalRows;

    @Schema(description = "是否同时清除了封面图片文件")
    private boolean coversRemoved;

    public void addRows(String table, int rows) {
        if (rows > 0) {
            tableRows.put(table, rows);
            totalRows += rows;
        }
    }
}
