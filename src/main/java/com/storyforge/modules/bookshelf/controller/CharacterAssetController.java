package com.storyforge.modules.bookshelf.controller;

import com.storyforge.common.api.R;
import com.storyforge.common.ratelimit.RateLimit;
import com.storyforge.modules.bookshelf.dto.AttributeRequest;
import com.storyforge.modules.bookshelf.dto.AttributeVO;
import com.storyforge.modules.bookshelf.dto.InventoryRequest;
import com.storyforge.modules.bookshelf.dto.InventoryVO;
import com.storyforge.modules.bookshelf.service.CharacterAssetService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 人物资产：自定义属性 + 背包。
 */
@Tag(name = "人物资产", description = "人物属性与背包（关联物品档案，或直接记货币 / 常规物资）")
@RestController
@RequestMapping("/api/bookshelf")
@RequiredArgsConstructor
public class CharacterAssetController {

    private final CharacterAssetService characterAssetService;

    @Operation(summary = "人物属性列表")
    @GetMapping("/characters/{characterId}/attributes")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<AttributeVO>> listAttributes(@PathVariable Long characterId) {
        return R.ok(characterAssetService.listAttributes(characterId));
    }

    @Operation(summary = "新增人物属性")
    @PostMapping("/characters/{characterId}/attributes")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<AttributeVO> createAttribute(@PathVariable Long characterId,
                                          @Valid @RequestBody AttributeRequest request) {
        return R.ok(characterAssetService.createAttribute(characterId, request));
    }

    @Operation(summary = "修改人物属性")
    @PutMapping("/attributes/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<AttributeVO> updateAttribute(@PathVariable Long id, @Valid @RequestBody AttributeRequest request) {
        return R.ok(characterAssetService.updateAttribute(id, request));
    }

    @Operation(summary = "删除人物属性")
    @DeleteMapping("/attributes/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<Void> deleteAttribute(@PathVariable Long id) {
        characterAssetService.deleteAttribute(id);
        return R.ok();
    }

    @Operation(summary = "人物背包列表", description = "ITEM 型会带出物品档案的描述与说明")
    @GetMapping("/characters/{characterId}/inventory")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<List<InventoryVO>> listInventory(@PathVariable Long characterId) {
        return R.ok(characterAssetService.listInventory(characterId));
    }

    @Operation(summary = "整本书的背包", description = "按人物分组返回：人物页背包视图一次取全，不用逐人请求")
    @GetMapping("/novels/{novelId}/inventory")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 600, windowSeconds = 60)
    public R<Map<Long, List<InventoryVO>>> novelInventory(@PathVariable Long novelId) {
        return R.ok(characterAssetService.inventoryByCharacter(novelId));
    }

    @Operation(summary = "添加背包条目",
            description = "entryType=ITEM 需传 itemId（名称取物品档案）；CURRENCY / MISC 需传名称")
    @PostMapping("/characters/{characterId}/inventory")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<InventoryVO> createInventory(@PathVariable Long characterId,
                                          @Valid @RequestBody InventoryRequest request) {
        return R.ok(characterAssetService.createInventory(characterId, request));
    }

    @Operation(summary = "修改背包条目", description = "类型与关联物品不可改；clearQuantity=true 可清空数量")
    @PutMapping("/inventory/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<InventoryVO> updateInventory(@PathVariable Long id,
                                          @Valid @RequestBody InventoryRequest request) {
        return R.ok(characterAssetService.updateInventory(id, request));
    }

    @Operation(summary = "删除背包条目")
    @DeleteMapping("/inventory/{id}")
    @RateLimit(dimension = RateLimit.Dimension.USER, limit = 300, windowSeconds = 60)
    public R<Void> deleteInventory(@PathVariable Long id) {
        characterAssetService.deleteInventory(id);
        return R.ok();
    }
}
