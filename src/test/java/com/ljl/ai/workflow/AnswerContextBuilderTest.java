package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerContextBuilderTest {

    @Test
    void shouldKeepEveryTaskWithinConfiguredBudgets() {
        WorkflowAnswerProperties properties = properties(240, 100, 1);
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析贵州茅台", List.of(
                completedTask("market", StockAnalysisTask.MARKET_DATA, "行情".repeat(120)),
                completedTask("news", StockAnalysisTask.NEWS_ANALYSIS, "新闻".repeat(120))));

        AnswerContextBuilder.Context context = new AnswerContextBuilder(properties).build(state);

        assertThat(context.content()).contains("MARKET_DATA", "NEWS_ANALYSIS", "内容已截断");
        assertThat(context.content().codePointCount(0, context.content().length())).isLessThanOrEqualTo(240);
        assertThat(context.truncatedTaskCount()).isEqualTo(2);
    }

    @Test
    void shouldUseOnlyTheMostRecentConfiguredResultHistory() {
        WorkflowAnswerProperties properties = properties(1_000, 500, 1);
        ExecutionTask task = completedTask("market", StockAnalysisTask.MARKET_DATA, "第一次行情");
        task.retry("重新获取");
        task.start();
        task.complete("第二次行情");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(task));

        String content = new AnswerContextBuilder(properties).build(state).content();

        assertThat(content).contains("第二次行情").doesNotContain("第一次行情");
    }

    @Test
    void shouldRetainFailureReasonWithoutResultContent() {
        WorkflowAnswerProperties properties = properties(1_000, 500, 2);
        ExecutionTask task = ExecutionTask.pending("financial", StockAnalysisTask.FINANCIAL_ANALYSIS);
        task.start();
        task.fail("财报服务超时");
        ExecutionState state = ExecutionState.planned("exec-1", "session-1", "分析", List.of(task));

        String content = new AnswerContextBuilder(properties).build(state).content();

        assertThat(content).contains("FINANCIAL_ANALYSIS", "FAILED", "财报服务超时");
    }

    private WorkflowAnswerProperties properties(int maxContextChars, int maxTaskChars, int maxHistoryEntries) {
        WorkflowAnswerProperties properties = new WorkflowAnswerProperties();
        properties.setMaxContextChars(maxContextChars);
        properties.setMaxTaskChars(maxTaskChars);
        properties.setMaxHistoryEntries(maxHistoryEntries);
        return properties;
    }

    private ExecutionTask completedTask(String id, StockAnalysisTask type, String result) {
        ExecutionTask task = ExecutionTask.pending(id, type);
        task.start();
        task.complete(result);
        return task;
    }
}
