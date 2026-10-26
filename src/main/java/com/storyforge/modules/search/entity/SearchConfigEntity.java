package com.storyforge.modules.search.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 联网搜索源配置。
 *
 * <p>{@code provider} 决定走哪个检索实现；{@code baseUrl} 允许覆盖默认地址
 * （SearXNG 自建实例、维基百科镜像等）。{@code apiKeyCipher} 仅在需要密钥的
 * 搜索源上使用，与 AI 接口密钥共用 {@code SecretCipher}。</p>
 */
@Data
@TableName("t_search_config")
public class SearchConfigEntity implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 配置名称，同一用户内唯一 */
    private String name;

    /** BING / DUCKDUCKGO / WIKIPEDIA / SEARXNG / TAVILY / SERPER */
    private String provider;

    private String baseUrl;

    private String apiKeyCipher;

    private String apiKeyMask;

    /** 默认返回条数 */
    private Integer defaultTopK;

    private String language;

    private String region;

    private Integer timeoutMs;

    private Boolean enabled;

    private Boolean isDefault;

    private String remark;

    private LocalDateTime lastTestAt;

    private Boolean lastTestOk;

    private Integer lastTestLatencyMs;

    private String lastTestMessage;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;

    @Version
    private Integer version;
}
