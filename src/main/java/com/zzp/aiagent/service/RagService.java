package com.zzp.aiagent.service;

import com.zzp.aiagent.model.dto.chat.ChatRequest;
import com.zzp.aiagent.domain.rag.RagContext;

/**
 * RAG 增强服务接口，负责根据请求构建三层增强上下文。
 */
public interface RagService {

    /**
     * 根据 ChatRequest 构建 RAG 上下文（三层增强：明确参考图 → RAG检索 → 风格模板）。
     *
     * @param request 对话请求
     * @return 增强上下文，可能为空（三层均无数据）
     */
    RagContext buildContext(ChatRequest request);
}
