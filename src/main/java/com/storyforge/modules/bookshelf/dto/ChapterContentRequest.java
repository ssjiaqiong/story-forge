package com.storyforge.modules.bookshelf.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 正文自动保存的请求体。
 *
 * <p>只带正文与客户端上次看到的版本号——自动保存<b>不碰标题、状态、关联</b>，
 * 免得把用户正在改的其它字段一起覆盖回去。</p>
 */
@Data
@Schema(description = "自动保存正文")
public class ChapterContentRequest {

    @Schema(description = "正文全文；允许为空串（作者清空正文是合法意图）")
    @NotNull(message = "正文不能为 null，清空请传空串")
    private String content;

    @Schema(description = "客户端上次读到的版本号；带上它即可检测「另一处已修改」。老前端可不传")
    private Integer version;
}
