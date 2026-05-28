package com.zzp.aiagent.controller;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.knowledge.KnowledgeService;
import com.zzp.aiagent.knowledge.model.AddKnowledgeRequest;
import com.zzp.aiagent.knowledge.model.KnowledgeAsset;
import com.zzp.aiagent.knowledge.storage.AssetStorage;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.util.List;

@RestController
@RequestMapping("/knowledge")
@Profile("!test")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final AssetStorage assetStorage;

    public KnowledgeController(KnowledgeService knowledgeService, AssetStorage assetStorage) {
        this.knowledgeService = knowledgeService;
        this.assetStorage = assetStorage;
    }

    @PostMapping("/add")
    public BaseResponse<KnowledgeAsset> add(@RequestBody AddKnowledgeRequest request) {
        ThrowUtils.throwIf(request.imageUrl() == null && request.imageBase64() == null,
                ErrorCode.PARAMS_ERROR, "请提供图片URL或Base64数据");
        return ResultUtils.success(knowledgeService.add(request));
    }

    @GetMapping("/search")
    public BaseResponse<List<KnowledgeAsset>> search(@RequestParam String query,
                                                     @RequestParam(defaultValue = "5") int topK) {
        ThrowUtils.throwIf(query == null || query.isBlank(), ErrorCode.PARAMS_ERROR, "搜索关键词不能为空");
        return ResultUtils.success(knowledgeService.semanticSearch(query, topK));
    }

    @DeleteMapping("/{assetId}")
    public BaseResponse<Void> remove(@PathVariable String assetId) {
        knowledgeService.remove(assetId);
        return ResultUtils.success(null);
    }

    @GetMapping("/files/{key}")
    public ResponseEntity<Resource> serveFile(@PathVariable String key) {
        InputStream input = assetStorage.load(key);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(new InputStreamResource(input));
    }
}
