package com.ljl.ai.workflow;

import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkflowCriticTest {

    private final WorkflowCritic critic = new WorkflowCritic();

    @Test
    void shouldRouteTrustedReflectionToAnswer() {
        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector.ReflectionDecision(
                true, List.of(), List.of(), "全部任务结果通过校验");

        assertEquals(WorkflowCritic.Route.ANSWER, critic.criticize(decision).route());
    }

    @Test
    void shouldRouteRetryBeforeAnyOtherFollowUp() {
        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector.ReflectionDecision(
                false, List.of("market"), List.of(StockAnalysisTask.NEWS_ANALYSIS), "行情结果为空");

        assertEquals(WorkflowCritic.Route.RETRY, critic.criticize(decision).route());
    }

    @Test
    void shouldRouteMissingNewsToSupplementalTask() {
        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector.ReflectionDecision(
                false, List.of(), List.of(StockAnalysisTask.NEWS_ANALYSIS), "缺少新闻分析");

        assertEquals(WorkflowCritic.Route.ADD_NEWS, critic.criticize(decision).route());
    }

    @Test
    void shouldRouteUnrecoverableFailureToFailed() {
        WorkflowReflector.ReflectionDecision decision = new WorkflowReflector.ReflectionDecision(
                false, List.of(), List.of(), "market超过最大重试次数");

        assertEquals(WorkflowCritic.Route.FAILED, critic.criticize(decision).route());
    }
}
