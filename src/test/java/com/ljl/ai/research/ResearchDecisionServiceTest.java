package com.ljl.ai.research;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.workflow.ExecutionState;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResearchDecisionServiceTest {

    @Test
    void shouldPersistDecisionWithOwnerSymbolAndEvidenceHash() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.insert(any(ResearchDecision.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ResearchDecisionService service = new ResearchDecisionService(mongoTemplate);

        ResearchDecision saved = service.save(completedDeepState());

        assertThat(saved.getDecisionId()).isNotBlank();
        assertThat(saved.getUserId()).isEqualTo("user-1");
        assertThat(saved.getSymbol()).isEqualTo("600519.SH");
        assertThat(saved.getAnalysisDate()).isEqualTo(LocalDate.of(2025, 12, 31));
        assertThat(saved.getEvidenceHash()).isEqualTo("evidence-hash");
        assertThat(saved.getRating()).isEqualTo(ResearchConclusion.Rating.BULLISH);
        assertThat(saved.getEvaluationHorizons()).containsExactly(1, 5, 20);
        assertThat(saved.getReviewStatus()).isEqualTo(ResearchDecision.ReviewStatus.PENDING);
    }

    @Test
    void shouldReturnExistingDecisionForSameExecutionIdIdempotently() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        ResearchDecision existing = ResearchDecision.pending("decision-1", "exec-1", "user-1",
                "600519.SH", LocalDate.of(2025, 12, 31), ResearchConclusion.Rating.BULLISH,
                0.8, "hash", "summary", "graph-v1");
        when(mongoTemplate.findOne(any(Query.class), eq(ResearchDecision.class))).thenReturn(existing);
        ResearchDecisionService service = new ResearchDecisionService(mongoTemplate);

        assertThat(service.save(completedDeepState())).isSameAs(existing);
        verify(mongoTemplate, never()).insert(any(ResearchDecision.class));
    }

    @Test
    void shouldQueryOnlyCompletedSameOwnerAndSymbolReviewsVisibleAtAnalysisDate() {
        MongoTemplate mongoTemplate = mock(MongoTemplate.class);
        when(mongoTemplate.find(any(Query.class), eq(ResearchDecision.class))).thenReturn(List.of());
        ResearchDecisionService service = new ResearchDecisionService(mongoTemplate);
        LocalDate asOf = LocalDate.of(2026, 2, 1);

        service.findCompletedReviews("user-1", "600519.SH", asOf);

        ArgumentCaptor<Query> query = ArgumentCaptor.forClass(Query.class);
        verify(mongoTemplate).find(query.capture(), eq(ResearchDecision.class));
        Document criteria = query.getValue().getQueryObject();
        assertThat(criteria.getString("userId")).isEqualTo("user-1");
        assertThat(criteria.getString("symbol")).isEqualTo("600519.SH");
        assertThat(criteria.getString("reviewStatus")).isEqualTo("COMPLETED");
        assertThat((Document) criteria.get("analysisDate")).containsEntry("$lte", asOf);
        assertThat((Document) criteria.get("outcomeAvailableAt")).containsEntry("$lte", asOf);
    }

    private ExecutionState completedDeepState() {
        LocalDate date = LocalDate.of(2025, 12, 31);
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析贵州茅台", List.of());
        state.setUserId("user-1");
        state.setGraphVersion("graph-v1");
        state.setPlan(AgentPlan.builder().intent("STOCK_ANALYSIS").symbol("600519.SH")
                .tasks(List.of(StockAnalysisTask.MARKET_DATA)).build());
        AnalysisContext context = new AnalysisContext("600519.SH", date, AnalysisContext.ResearchMode.DEEP,
                "exec-1", "trace-1", "user-1", "session-1");
        state.setAnalysisContext(context);
        state.setEvidencePack(new EvidencePack(context, Map.of(), List.of(), List.of(),
                Instant.parse("2025-12-31T08:00:00Z"), "evidence-hash", "evidence"));
        state.setResearchConclusion(new ResearchConclusion(ResearchConclusion.Rating.BULLISH, 0.8,
                "盈利质量改善", List.of(), List.of("估值风险"), date, false, List.of()));
        return state;
    }
}
