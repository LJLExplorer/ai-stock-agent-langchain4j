package com.ljl.ai.workflow;

import com.ljl.ai.planner.AgentPlan;
import com.ljl.ai.planner.StockAnalysisTask;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsRecoveryAdvisorTest {

    @Test
    void appliesValidatedQueryAndWindowToRetryTask() {
        ChatLanguageModel model = model("{\"action\":\"NARROW_WINDOW\",\"query\":\"贵州茅台 交易所公告 业绩\",\"days\":7}");
        WorkflowReflector reflector = new WorkflowReflector(2, new WorkflowResultValidator(),
                new NewsRecoveryAdvisor(model, true));
        ExecutionState state = invalidNewsState();

        var decision = reflector.reflect(state);

        assertThat(decision.retryTaskIds()).containsExactly("news");
        assertThat(decision.recoveryDirectives().get("news").query()).isEqualTo("贵州茅台 交易所公告 业绩");
        assertThat(decision.recoveryDirectives().get("news").days()).isEqualTo(7);

        state.start();
        state.setReflectionDecision(decision);
        WorkflowAgentState graphState = WorkflowAgentState.from(state);
        ExecutionTask retried = graphState.apply(new StockAnalysisWorkflow().retry(graphState))
                .toExecutionState().getTasks().getFirst();
        assertThat(retried.getStatus()).isEqualTo(TaskStatus.RETRYING);
        assertThat(retried.getRecoveryQuery()).isEqualTo("贵州茅台 交易所公告 业绩");
        assertThat(retried.getNewsWindowDays()).isEqualTo(7);
    }

    @Test
    void invalidOrDuplicateAdviceStopsMechanicalRetry() {
        ChatLanguageModel model = model("{\"action\":\"REFINE_QUERY\",\"query\":\"分析贵州茅台\",\"days\":30}");
        WorkflowReflector reflector = new WorkflowReflector(2, new WorkflowResultValidator(),
                new NewsRecoveryAdvisor(model, true));
        ExecutionState state = invalidNewsState();

        var decision = reflector.reflect(state);

        assertThat(decision.trusted()).isFalse();
        assertThat(decision.retryTaskIds()).isEmpty();
        assertThat(decision.reason()).contains("新闻资料不足");
    }

    @Test
    void switchesRetryToOfficialSources() {
        ChatLanguageModel model = model("{\"action\":\"OFFICIAL_ONLY\",\"query\":\"分析贵州茅台\",\"days\":30}");
        WorkflowReflector reflector = new WorkflowReflector(2, new WorkflowResultValidator(),
                new NewsRecoveryAdvisor(model, true));
        ExecutionState state = invalidNewsState();

        var decision = reflector.reflect(state);

        assertThat(decision.retryTaskIds()).containsExactly("news");
        assertThat(decision.recoveryDirectives().get("news").officialOnly()).isTrue();
    }

    private ChatLanguageModel model(String json) {
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from(json)).build());
        return model;
    }

    private ExecutionState invalidNewsState() {
        ExecutionTask task = ExecutionTask.pending("news", StockAnalysisTask.NEWS_ANALYSIS);
        task.start();
        task.complete("[{\"title\":\"新闻\",\"url\":\"invalid\"}]");
        ExecutionState state = ExecutionState.planned("execution", "session", "分析贵州茅台", List.of(task));
        state.setPlan(AgentPlan.builder().symbol("600519.SH").tasks(List.of(StockAnalysisTask.NEWS_ANALYSIS)).build());
        return state;
    }
}
