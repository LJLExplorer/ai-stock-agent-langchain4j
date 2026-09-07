package com.ljl.ai.service;

import com.ljl.ai.agent.AgentPlannerAssistant;
import com.ljl.ai.agent.QueryRewriteAssistant;
import com.ljl.ai.agent.StockAnalysisAssistant;
import com.ljl.ai.memory.ChatMemoryService;
import com.ljl.ai.memory.ConversationTopicStore;
import com.ljl.ai.memory.RedisChatMemoryProvider;
import com.ljl.ai.memory.ShortTermSummaryService;
import com.ljl.ai.model.dto.ChatRequest;
import com.ljl.ai.model.dto.ChatResponse;
import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.model.entity.ChatSession;
import com.ljl.ai.model.entity.KnowledgeSource;
import com.ljl.ai.model.entity.RagTrace;
import com.ljl.ai.rag.RetrievalResult;
import com.ljl.ai.rag.RetrievalService;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.WorkflowRunner;
import dev.langchain4j.memory.ChatMemory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/** 覆盖真实服务之间的数据传递、持久化顺序、失败恢复和并发边界。 */
class ChatServiceOrchestrationTest {
    private final ChatServiceFixture fixture = new ChatServiceFixture();
    private final ChatMemoryService messages = mock(ChatMemoryService.class);
    private final RedisChatMemoryProvider memory = mock(RedisChatMemoryProvider.class);
    private final ShortTermSummaryService summaries = mock(ShortTermSummaryService.class);
    private final ConversationTopicStore topics = mock(ConversationTopicStore.class);
    private final StockAnalysisAssistant assistant = mock(StockAnalysisAssistant.class);
    private final StockAnalysisAssistant toolsAssistant = mock(StockAnalysisAssistant.class);
    private final AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
    private final RetrievalService retrieval = mock(RetrievalService.class);
    private final RagTraceService traces = mock(RagTraceService.class);
    private final WorkflowRunner workflow = mock(WorkflowRunner.class);
    private final String modelMemoryId = ConversationTopicStore.topicMemoryId("u:s", "600519");

    @BeforeEach
    void setUp() {
        setField(fixture.context, "chatMemoryService", messages);
        setField(fixture.context, "shortTermSummaryService", summaries);
        setField(fixture.context, "conversationTopicStore", topics);
        setField(fixture.context, "queryRewriteAssistant", mock(QueryRewriteAssistant.class));
        setField(fixture.context, "longTermMemoryService", mock(LongTermMemoryService.class));
        setField(fixture.execution, "stockAnalysisAssistantWithoutTools", assistant);
        setField(fixture.execution, "stockAnalysisAssistant", toolsAssistant);
        setField(fixture.execution, "agentPlannerAssistant", planner);
        setField(fixture.execution, "workflowRunner", workflow);
        setField(fixture.rag, "retrievalService", retrieval);
        setField(fixture.assembler, "chatMemoryProvider", memory);
        setField(fixture.persistence, "chatMemoryService", messages);
        setField(fixture.persistence, "shortTermSummaryService", summaries);
        setField(fixture.persistence, "conversationTopicStore", topics);
        setField(fixture.persistence, "ragTraceService", traces);
        setField(fixture.failure, "chatMemoryProvider", memory);
        when(messages.getOrCreateSession(any(), eq("u"), any())).thenAnswer(call ->
                ChatSession.builder().sessionId(call.getArgument(0)).userId("u").status("ACTIVE").build());
        when(messages.getSession("s")).thenReturn(ChatSession.builder()
                .sessionId("s").userId("u").status("ACTIVE").build());
        when(messages.saveAssistantMessage(anyString(), anyString(), any()))
                .thenReturn(ChatMessage.builder().messageId("m").build());
        when(topics.get(anyString())).thenReturn(ConversationTopicStore.TopicState.empty());
        ChatMemory chatMemory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(chatMemory);
        when(chatMemory.messages()).thenReturn(List.of());
        when(assistant.chatWithMemory(anyString(), anyString(), anyString())).thenReturn("回答");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void ragShouldUseCanonicalQuestionAndPersistOriginalMessageBeforeSummaryAndTrace(boolean withScore) {
        ChatRequest request = request("s", true, false);
        request.setMessage("分析技术面");
        request.setOrderId("600519");
        String query = "分析技术面\n当前用户正在咨询股票：600519";
        List<RetrievalResult> matches = List.of(RetrievalResult.builder()
                .documentId("doc").title("研报").similarity(withScore ? 0.9D : null).build());
        List<KnowledgeSource> sources = List.of(KnowledgeSource.builder().documentId("doc").build());
        when(retrieval.retrieve(query)).thenReturn(matches);
        when(retrieval.buildAugmentedContext(query, matches)).thenReturn("知识上下文");
        when(retrieval.toKnowledgeSources(matches)).thenReturn(sources);
        when(summaries.get(modelMemoryId)).thenReturn("当前话题摘要");
        when(assistant.chatWithRag(eq(modelMemoryId), eq(query), eq("知识上下文"), anyString()))
                .thenReturn("回答");
        doThrow(new IllegalStateException("summary unavailable")).when(summaries).refresh(modelMemoryId);

        MDC.put("traceId", "outer-trace");
        ChatResponse response;
        try {
            response = fixture.service.chat(request);
            assertThat(MDC.get("traceId")).isEqualTo("outer-trace");
        } finally {
            MDC.clear();
        }

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getMessageId()).isEqualTo("m");
        assertThat(response.getKnowledgeSources()).containsExactlyElementsOf(sources);
        verify(assistant).chatWithRag(eq(modelMemoryId), eq(query), eq("知识上下文"), contains("当前话题摘要"));
        verifyNoInteractions(planner, workflow, toolsAssistant);
        var order = inOrder(messages, topics, summaries, traces);
        order.verify(messages).saveUserMessage("s", "分析技术面");
        order.verify(messages).saveAssistantMessage("s", "回答", sources);
        order.verify(topics).activate("u:s", "600519");
        order.verify(summaries).refresh(modelMemoryId);
        ArgumentCaptor<RagTrace> trace = ArgumentCaptor.forClass(RagTrace.class);
        order.verify(traces).saveBestEffort(trace.capture());
        order.verify(messages).updateSessionTitle("s", "分析技术面");
        assertThat(trace.getValue().getMessageId()).isEqualTo("m");
        assertThat(trace.getValue().getSuccess()).isTrue();
        assertThat(trace.getValue().getTopScore()).isEqualTo(withScore ? 0.9D : null);
    }

    @Test
    void completedWorkflowShouldReuseAcceptedIdAndSkipAssistantGeneration() {
        when(workflow.run(any())).thenAnswer(call -> {
            ExecutionState state = call.getArgument(0);
            state.setFinalAnswer("工作流结论");
            return state;
        });

        ChatResponse response = fixture.service.chat(request("s", false, true), "accepted-id");

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getContent()).isEqualTo("工作流结论");
        ArgumentCaptor<ExecutionState> state = ArgumentCaptor.forClass(ExecutionState.class);
        verify(workflow).run(state.capture());
        assertThat(state.getValue().getExecutionId()).isEqualTo("accepted-id");
        assertThat(state.getValue().getUserId()).isEqualTo("u");
        assertThat(response.getToolInvocations()).hasSize(1);
        verifyNoInteractions(retrieval, assistant, toolsAssistant, planner);
        verify(messages, never()).getOrCreateSession(any(), any(), any());
    }

    @Test
    void noRagMatchesShouldUseMemoryAssistantAndStillSaveTrace() {
        when(retrieval.retrieve(anyString())).thenReturn(List.of());
        when(retrieval.buildAugmentedContext(anyString(), anyList())).thenReturn("空检索提示");
        ChatResponse response = fixture.service.chat(request("s", true, false));
        assertThat(response.getSuccess()).isTrue();
        verify(assistant).chatWithMemory(eq(modelMemoryId), anyString(), anyString());
        verify(assistant, never()).chatWithRag(any(), any(), any(), any());
        verify(traces).saveBestEffort(any());
    }

    @Test
    void assistantFailureShouldClearOnlyActiveTopicAndNotPersistOrAdvanceTopic() {
        when(assistant.chatWithMemory(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("url error: private details"));

        ChatResponse response = fixture.service.chat(request("s", false, false));

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getContent()).doesNotContain("private details");
        assertThat(MDC.get("traceId")).isNull();
        verify(memory).clearMemory(modelMemoryId);
        verify(messages, never()).saveUserMessage(any(), any());
        verify(topics, never()).activate(any(), any());
        verify(summaries, never()).refresh(any());
    }

    @Test
    void messageStorageFailureShouldNotAdvanceTopicOrRefreshSummary() {
        when(messages.saveAssistantMessage(any(), any(), any())).thenThrow(new IllegalStateException("storage failed"));
        ChatResponse response = fixture.service.chat(request("s", false, false));
        assertThat(response.getSuccess()).isFalse();
        verify(topics, never()).activate(any(), any());
        verify(summaries, never()).refresh(any());
        verify(messages, never()).updateSessionTitle(any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldSerializeSameSessionAndAllowDifferentSessions(boolean sameSession) throws Exception {
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondEntered = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(assistant.chatWithMemory(anyString(), anyString(), anyString())).thenAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                firstEntered.countDown();
                assertThat(releaseFirst.await(5, TimeUnit.SECONDS)).isTrue();
            } else {
                secondEntered.countDown();
            }
            return "回答";
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> fixture.service.chat(request("s", false, false)));
            try {
                assertThat(firstEntered.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    secondStarted.countDown();
                    return fixture.service.chat(request(sameSession ? "s" : "other", false, false));
                });
                assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(secondEntered.await(sameSession ? 100 : 5000, TimeUnit.MILLISECONDS)).isEqualTo(!sameSession);
                releaseFirst.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS).getSuccess()).isTrue();
                assertThat(second.get(5, TimeUnit.SECONDS).getSuccess()).isTrue();
            } finally {
                releaseFirst.countDown();
            }
        }
    }

    private ChatRequest request(String sessionId, boolean rag, boolean tools) {
        return ChatRequest.builder().sessionId(sessionId).userId("u").message("分析600519技术面")
                .enableRag(rag).enableTools(tools).build();
    }
}
