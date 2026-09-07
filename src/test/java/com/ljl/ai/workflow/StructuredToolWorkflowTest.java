package com.ljl.ai.workflow;

import com.ljl.ai.client.FinancialDataClient;
import com.ljl.ai.client.MarketDataClient;
import com.ljl.ai.model.dto.ToolResult;
import com.ljl.ai.model.entity.StockQuote;
import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import com.ljl.ai.research.AnalysisContext;
import com.ljl.ai.research.FinancialFact;
import com.ljl.ai.tools.FinancialAnalysisTool;
import com.ljl.ai.tools.MarketDataTool;
import com.ljl.ai.tools.NewsRagTool;
import com.ljl.ai.tools.TechnicalAnalysisTool;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StructuredToolWorkflowTest {
    private static final LocalDate DATE = LocalDate.of(2025, 12, 31);

    @Test
    void olderCheckpointCannotSkipNewValidationByResumingAtAnswer() {
        var task = ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA);
        task.start();
        task.complete("旧的文本结果");
        var state = state(List.of(task));
        state.setGraphVersion("stock-analysis-v2");
        state.setPlanHash(WorkflowRunner.planHash(state.getPlan()));
        state.setNextNode("ANSWER");
        var store = mock(ExecutionStateStore.class);
        when(store.load(state.getExecutionId())).thenReturn(java.util.Optional.of(state));
        assertThatThrownBy(() -> new WorkflowRunner(mock(StockAnalysisWorkflow.class), store).resume(state.getExecutionId()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("INCOMPATIBLE_CHECKPOINT");
    }

    @Test
    void realTechnicalAndFinancialToolsProduceValidatedNumericFacts() throws Exception {
        var marketClient = mock(MarketDataClient.class);
        var financialClient = mock(FinancialDataClient.class);
        when(marketClient.getDailyBars("600519.SH", 60, DATE)).thenReturn(IntStream.range(0, 20)
                .mapToObj(i -> new MarketDataClient.DailyBar(DATE.minusDays(19 - i).toString(),
                        BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, 100L)).toList());
        when(financialClient.getLatest("600519.SH", "latest", DATE)).thenReturn(new FinancialDataClient.FinancialSnapshot(
                Map.of("symbol", "600519.SH", "source", "Eastmoney", "sourceUrl", "https://example.test/report",
                        "revenue", "1000", "netProfit", "-10", "revenueGrowth", "-2", "netProfitGrowth", "-150",
                        "roe", "-1", "operatingCashFlow", "-20"),
                DATE.minusDays(90), DATE.minusDays(30), FinancialFact.TemporalStatus.VERIFIED));
        var executor = new StockAnalysisTaskExecutor(mock(MarketDataTool.class), new TechnicalAnalysisTool(marketClient),
                new FinancialAnalysisTool(financialClient), mock(NewsRagTool.class));
        var technical = ExecutionTask.pending("technical", StockAnalysisTask.TECHNICAL_ANALYSIS);
        var financial = ExecutionTask.pending("financial", StockAnalysisTask.FINANCIAL_ANALYSIS);
        var state = state(List.of(technical, financial));
        var node = new StockAnalysisTaskNode(executor);
        node.execute(state, technical);
        node.execute(state, financial);

        assertThat(new WorkflowReflector().reflect(state).issues()).isEmpty();
        assertThat(technical.getCurrentEvidence()).hasSize(4);
        assertThat(financial.getCurrentEvidence()).hasSize(6);
        assertThat(state.getEvidencePack().modelView()).contains("FINANCIAL/netProfit=-10", "TECHNICAL/ma20=10");
        assertThat(financial.getResult()).startsWith("{").doesNotContain("财务数据（");
    }

    @Test
    void graphRetriesInvalidStructuredPriceAndAnswersOnlyWithCorrectedEvidence() {
        var executor = mock(StockAnalysisTaskExecutor.class);
        var bad = StockQuote.builder().symbol("600519.SH").price(new BigDecimal("-1")).timestamp(DATE.atTime(15, 0)).build();
        var good = StockQuote.builder().symbol("600519.SH").price(new BigDecimal("1500")).timestamp(DATE.atTime(15, 0)).build();
        doReturn(ToolResult.success(bad), ToolResult.success(good)).when(executor)
                .executeWithContext(any(), any(), anyString(), anyString());
        var state = state(List.of(ExecutionTask.pending("market", StockAnalysisTask.MARKET_DATA)));
        var answer = mock(WorkflowAnswerGenerator.class);
        var result = new StockAnalysisWorkflow(new StockAnalysisTaskNode(executor),
                new WorkflowReflector(), new WorkflowCritic(), answer).run(state);

        assertThat(result.getCriticDecision().route()).isEqualTo(WorkflowCritic.Route.ANSWER);
        assertThat(result.getTasks().getFirst().getAttempts()).isEqualTo(2);
        assertThat(result.getTasks().getFirst().getEvidence()).hasSize(2);
        assertThat(result.getEvidencePack().modelView()).contains("price=1500").doesNotContain("price=-1");
        verify(executor, times(2)).executeWithContext(any(), any(), anyString(), anyString());
        verify(answer).generate(any());
    }

    private ExecutionState state(List<ExecutionTask> tasks) {
        var state = ExecutionState.planned("structured-tool", "session", "分析", tasks);
        state.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(tasks.stream().map(ExecutionTask::getTaskType).toList()).build());
        state.setAnalysisContext(new AnalysisContext("600519.SH", DATE, null, null, null, null, null));
        return state;
    }
}
