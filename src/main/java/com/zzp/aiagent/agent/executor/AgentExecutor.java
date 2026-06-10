package com.zzp.aiagent.agent.executor;

import org.springframework.ai.chat.client.ChatClientResponse;
import reactor.core.publisher.Flux;

public interface AgentExecutor {
    com.zzp.aiagent.agent.AgentResult execute(AgentInput input);
    Flux<ChatClientResponse> stream(AgentInput input);
}
