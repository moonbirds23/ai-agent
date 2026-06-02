package com.zzp.aiagent.service;

import com.zzp.aiagent.domain.rag.RagRewriteResult;

public interface RagQueryRewriteService {

    RagRewriteResult rewrite(String userMessage, String conversationHistory);
}
