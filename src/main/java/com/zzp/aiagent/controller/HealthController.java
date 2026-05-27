package com.zzp.aiagent.controller;

import com.zzp.aiagent.common.BaseResponse;
import com.zzp.aiagent.common.ResultUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/health")
public class HealthController {

    @GetMapping
    public BaseResponse<String> healthCheck() {
        return ResultUtils.success("ok");
    }
}
