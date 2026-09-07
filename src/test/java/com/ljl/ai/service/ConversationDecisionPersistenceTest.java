package com.ljl.ai.service;

import com.ljl.ai.model.entity.ChatMessage;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.ResearchConclusion;
import com.ljl.ai.research.ResearchDecisionService;
import com.ljl.ai.workflow.ExecutionState;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationDecisionPersistenceTest {

    @Test
    void shouldSaveDeepDecisionOnlyAfterBusinessAssistantMessageWasSaved() {
        ConversationPersistenceService service = new ConversationPersistenceService();
        ResearchDecisionService decisionService = mock(ResearchDecisionService.class);
        ReflectionTestUtils.setField(service, "researchDecisionService", decisionService);
        ExecutionState deep = executionWithConclusion(AnalysisContext.ResearchMode.DEEP);

        service.persistResearchDecisionAfterMessage(deep, mock(ChatMessage.class));
        service.persistResearchDecisionAfterMessage(deep, null);
        service.persistResearchDecisionAfterMessage(
                executionWithConclusion(AnalysisContext.ResearchMode.STANDARD), mock(ChatMessage.class));

        verify(decisionService, times(1)).save(deep);
    }

    @Test
    void shouldNotFailResponseWhenDecisionPersistenceFails() {
        ConversationPersistenceService service = new ConversationPersistenceService();
        ResearchDecisionService decisionService = mock(ResearchDecisionService.class);
        ReflectionTestUtils.setField(service, "researchDecisionService", decisionService);
        ExecutionState state = executionWithConclusion(AnalysisContext.ResearchMode.DEEP);
        when(decisionService.save(state)).thenThrow(new IllegalStateException("mongo unavailable"));

        service.persistResearchDecisionAfterMessage(state, mock(ChatMessage.class));

        verify(decisionService).save(state);
    }

    private ExecutionState executionWithConclusion(AnalysisContext.ResearchMode mode) {
        LocalDate date = LocalDate.of(2026, 2, 1);
        ExecutionState state = ExecutionState.planned("exec-" + mode, "session-1", "分析", List.of());
        state.setUserId("user-1");
        state.setAnalysisContext(new AnalysisContext("600519.SH", date, mode,
                state.getExecutionId(), "trace-1", "user-1", "session-1"));
        state.setResearchConclusion(new ResearchConclusion(ResearchConclusion.Rating.NEUTRAL, 0.7,
                "结论", List.of(), List.of(), date, false, List.of()));
        return state;
    }
}
