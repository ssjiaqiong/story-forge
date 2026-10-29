package com.storyforge.modules.system.controller;

import com.storyforge.common.api.R;
import com.storyforge.modules.system.dto.SystemInfoVO;
import com.storyforge.modules.system.service.SystemInfoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统信息：公开的部署元数据。
 *
 * <p>只有版本号与构建时间，不含任何配置、路径或环境细节，故免登录——
 * 登录页的页脚同样要显示版本，此时还没有令牌。</p>
 */
@Tag(name = "系统信息", description = "当前部署的版本号与构建时间，免登录")
@RestController
@RequestMapping("/api/system")
@RequiredArgsConstructor
public class SystemInfoController {

    private final SystemInfoService systemInfoService;

    @Operation(summary = "部署信息",
            description = "返回构建产物里冻结的版本号与构建时间；版本号唯一来源是 pom.xml 的 version")
    @GetMapping("/info")
    public R<SystemInfoVO> info() {
        return R.ok(systemInfoService.info());
    }
}
