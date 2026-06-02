package com.zzp.aiagent.controller;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import com.zzp.aiagent.common.ThrowUtils;
import com.zzp.aiagent.exception.BusinessException;
import com.zzp.aiagent.exception.ErrorCode;
import com.zzp.aiagent.service.StyleTemplateService;
import com.zzp.aiagent.domain.template.StyleTemplate;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Profile("!test")
@RestController
@RequestMapping("/template")
@RequiredArgsConstructor
@Tag(name = "风格模板", description = "系统内置AI生图风格模板")
public class TemplateController {

    private final StyleTemplateService templateService;

    @GetMapping
    @Operation(summary = "获取全部模板", description = "返回所有系统内置风格模板列表")
    public BaseResponse<List<StyleTemplate>> listAll() {
        return ResultUtils.success(templateService.listAll());
    }

    @GetMapping("/{code}")
    @Operation(summary = "按code获取模板", description = "根据模板code查找单个模板详情")
    public BaseResponse<StyleTemplate> getByCode(@PathVariable String code) {
        ThrowUtils.throwIf(code == null || code.isBlank(),
                ErrorCode.PARAMS_ERROR, "模板code不能为空");
        return ResultUtils.success(templateService.getByCode(code)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.PARAMS_ERROR, "模板不存在: " + code)));
    }
}
