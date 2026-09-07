package com.ljl.ai.service;

import com.ljl.ai.agent.AgentPlannerAssistant;
import com.ljl.ai.planner.PlanValidator;
import com.ljl.ai.planner.StockAnalysisTask;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AgentExecutionPlannerTest {

    @Test
    void shouldValidatePlannerJsonBeforeExecution() {
        AgentExecutionService chatService = new AgentExecutionService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("分析贵州茅台最近为什么跌"))
                .thenReturn("{\"intent\":\"STOCK_ANALYSIS\",\"symbol\":\"600519\",\"tasks\":[\"MARKET_DATA\",\"NEWS_ANALYSIS\"]}");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService.planForExecution("分析贵州茅台最近为什么跌").orElseThrow();

        assertEquals("600519.SH", result.plan().getSymbol());
        assertEquals(2, result.toolNames().size());
        verify(planner).plan("分析贵州茅台最近为什么跌");
    }

    @Test
    void shouldPlanExplicitStockCodeLocallyWithoutCallingPlanner() {
        AgentExecutionService chatService = new AgentExecutionService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService.planForExecution("请对600519做技术分析").orElseThrow();

        assertEquals("600519.SH", result.plan().getSymbol());
        assertEquals(List.of(StockAnalysisTask.TECHNICAL_ANALYSIS), result.plan().getTasks());
        verifyNoInteractions(planner);
    }

    @Test
    void shouldSafelyFallbackWhenPlannerReturnsInvalidJsonOrIllegalPlan() {
        AgentExecutionService chatService = new AgentExecutionService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("非法计划")).thenReturn("{not-json}");
        when(planner.plan("越界任务")).thenReturn("{\"intent\":\"STOCK_ANALYSIS\",\"symbol\":\"600519\",\"tasks\":[\"PORTFOLIO_ANALYSIS\"]}");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        assertTrue(chatService.planForExecution("非法计划").isEmpty());
        assertTrue(chatService.planForExecution("越界任务").isEmpty());
    }

    @Test
    void shouldExtractPlanJsonWhenPlannerAddsDisclaimerAroundIt() {
        AgentExecutionService chatService = new AgentExecutionService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("带免责声明的计划")).thenReturn(
                "⚠️ 温馨提示：股市数据瞬息万变，以上信息仅供学习参考，不构成任何投资建议。\n"
                        + "{\"intent\":\"STOCK_ANALYSIS\",\"symbol\":\"600519\","
                        + "\"tasks\":[\"MARKET_DATA\"]}");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        assertTrue(chatService.planForExecution("带免责声明的计划").isPresent());
    }

    @Test
    void shouldInferRestrictedPlanWhenPlannerReturnsMarkdownAnalysis() {
        AgentExecutionService chatService = new AgentExecutionService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("请查询600511实时行情并分析相关新闻是否适合买入"))
                .thenReturn("### 标的确认\n600511 国药股份\n### 实时行情与新闻分析\n仅供研究参考");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService
                .planForExecution("请查询600511实时行情并分析相关新闻是否适合买入")
                .orElseThrow();

        assertEquals("600511.SH", result.plan().getSymbol());
        assertEquals(2, result.toolNames().size());
    }

    @Test
    void shouldPreferRestrictedUserIntentOverVerbosePlannerResponse() {
        AgentExecutionService chatService = new AgentExecutionService();
        AgentPlannerAssistant planner = mock(AgentPlannerAssistant.class);
        when(planner.plan("查询600511并给出购买建议")).thenReturn(
                "为您查询到 **国药股份（600511.SH）** 的最新实时行情如下：\n"
                        + "* **当前价格**：26.71元\n* **今日涨跌**：上涨 1.71%\n"
                        + "**今日行情简析**：整体呈现震荡上涨态势。\n"
                        + "关于您之前提到的“分析相关新闻及购买建议”，建议结合近期公司财报判断。");
        ReflectionTestUtils.setField(chatService, "agentPlannerAssistant", planner);

        PlanValidator.ValidatedPlan result = chatService.planForExecution("查询600511并给出购买建议").orElseThrow();

        assertEquals("600511.SH", result.plan().getSymbol());
        assertEquals(List.of(StockAnalysisTask.NEWS_ANALYSIS), result.plan().getTasks());
        verifyNoInteractions(planner);
    }

    @Test
    void shouldRejectPlannerOutputWithoutCompleteJsonObject() {
        assertThrows(IllegalArgumentException.class,
                () -> com.ljl.ai.support.ModelJsonExtractor.extractJsonObject("只有免责声明，没有计划"));
        assertThrows(IllegalArgumentException.class,
                () -> com.ljl.ai.support.ModelJsonExtractor.extractJsonObject("{\"intent\":\"STOCK_ANALYSIS\""));
    }

}
