package com.zzp.aiagent.rag.enhance;

import java.util.List;

public interface RagQueryRewriteService {

    RagRewriteResult rewrite(String userMessage, String conversationHistory);
}
