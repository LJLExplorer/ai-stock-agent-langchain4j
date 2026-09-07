package com.ljl.ai.service;

import com.ljl.ai.memory.ConversationContextService;
import com.ljl.ai.rag.RagPipelineService;

/** 使用真实的职责服务组装对话入口，测试按边界注入基础设施替身。 */
final class ChatServiceFixture {
    final ConversationContextService context = new ConversationContextService();
    final AgentExecutionService execution = new AgentExecutionService();
    final RagPipelineService rag = new RagPipelineService();
    final ResponseAssembler assembler = new ResponseAssembler();
    final ConversationPersistenceService persistence = new ConversationPersistenceService();
    final ChatFailureHandler failure = new ChatFailureHandler();
    final ChatService service = new ChatService(context, execution, rag, assembler, persistence, failure);
}
