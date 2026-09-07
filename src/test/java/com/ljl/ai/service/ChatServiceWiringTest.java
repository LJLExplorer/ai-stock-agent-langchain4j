package com.ljl.ai.service;

import com.ljl.ai.agent.AgentConfig;
import com.ljl.ai.agent.AgentPlannerAssistant;
import com.ljl.ai.agent.QueryRewriteAssistant;
import com.ljl.ai.agent.StockAnalysisAssistant;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.memory.ConversationContextService;
import com.ljl.ai.memory.ConversationTopicStore;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.entity.ChatSession;
import com.ljl.ai.rag.RagPipelineService;
import com.ljl.ai.rag.RetrievalService;
import com.ljl.ai.research.AnalysisContextResolver;
import com.ljl.ai.research.DecisionReviewService;
import com.ljl.ai.research.ResearchDecisionService;
import com.ljl.ai.workflow.WorkflowRunner;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ChatServiceWiringTest {
    @Test
    void springShouldWireExtractedServicesAndSelectAssistantWithoutTools() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var beans = context.getBeanFactory();
            ChatMemoryService messages = mock(ChatMemoryService.class);
            StockAnalysisAssistant assistant = mock(StockAnalysisAssistant.class);
            StockAnalysisAssistant toolsAssistant = mock(StockAnalysisAssistant.class);
            beans.registerSingleton("chatMemoryService", messages);
            beans.registerSingleton("stockAnalysisAssistantWithoutTools", assistant);
            beans.registerSingleton("stockAnalysisAssistant", toolsAssistant);
            beans.registerSingleton("agentPlannerAssistant", mock(AgentPlannerAssistant.class));
            beans.registerSingleton("queryRewriteAssistant", mock(QueryRewriteAssistant.class));
            beans.registerSingleton("agentConfig", mock(AgentConfig.class));
            beans.registerSingleton("workflowRunner", mock(WorkflowRunner.class));
            beans.registerSingleton("chatMemoryProvider", mock(RedisChatMemoryProvider.class));
            beans.registerSingleton("shortTermSummaryService", mock(ShortTermSummaryService.class));
            ConversationTopicStore topics = mock(ConversationTopicStore.class);
            beans.registerSingleton("conversationTopicStore", topics);
            beans.registerSingleton("longTermMemoryService", mock(LongTermMemoryService.class));
            beans.registerSingleton("ragTraceService", mock(RagTraceService.class));
            beans.registerSingleton("analysisContextResolver", mock(AnalysisContextResolver.class));
            beans.registerSingleton("decisionReviewService", mock(DecisionReviewService.class));
            beans.registerSingleton("researchDecisionService", mock(ResearchDecisionService.class));
            beans.registerSingleton("retrievalService", mock(RetrievalService.class));
            context.register(ChatService.class, ConversationContextService.class, AgentExecutionService.class,
                    RagPipelineService.class, ResponseAssembler.class, ConversationPersistenceService.class,
                    ChatFailureHandler.class);
            context.refresh();
            when(messages.getOrCreateSession(any(), any(), any()))
                    .thenReturn(ChatSession.builder().sessionId("s").build());
            when(topics.get(any())).thenReturn(ConversationTopicStore.TopicState.empty());
            when(assistant.chatWithMemory(anyString(), anyString(), anyString())).thenReturn("回答");

            var response = context.getBean(ChatService.class).chat(ChatRequest.builder()
                    .userId("u").message("分析600519技术面").enableTools(false).enableRag(false).build());

            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getContent()).isEqualTo("回答");
            verifyNoInteractions(toolsAssistant);
        }
    }
}
