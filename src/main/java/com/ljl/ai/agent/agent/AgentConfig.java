package com.ljl.ai.agent.agent;

import com.ljl.ai.agent.config.AgentToolConfig;
import com.ljl.ai.agent.memoery.MongoChatMemoryProvider;
import com.ljl.ai.agent.tools.FinancialAnalysisTool;
import com.ljl.ai.agent.tools.MarketDataTool;
import com.ljl.ai.agent.tools.NewsRagTool;
import com.ljl.ai.agent.tools.PortfolioAnalysisTool;
import com.ljl.ai.agent.tools.StockComparisonTool;
import com.ljl.ai.agent.tools.TechnicalAnalysisTool;
import com.ljl.ai.agent.tools.TimeSeriesPredictionTool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Agent配置
 * 配置LangChain4j AiService
 */
@Slf4j
@Configuration
public class AgentConfig {

    @Resource
    @Qualifier("openAiChatModel")
    private ChatLanguageModel chatLanguageModel;

    @Resource
    private MongoChatMemoryProvider chatMemoryProvider;

    @Resource
    private MarketDataTool marketDataTool;

    @Resource
    private TechnicalAnalysisTool technicalAnalysisTool;

    @Resource
    private FinancialAnalysisTool financialAnalysisTool;

    @Resource
    private NewsRagTool newsRagTool;

    @Resource
    private TimeSeriesPredictionTool timeSeriesPredictionTool;

    @Resource
    private StockComparisonTool stockComparisonTool;

    @Resource
    private PortfolioAnalysisTool portfolioAnalysisTool;

    @Resource
    private AgentToolConfig agentToolConfig;

    private Map<String, Object> toolRegistry() {
        Map<String, Object> registry = new LinkedHashMap<>();
        register(registry, "getRealtimeQuote", marketDataTool);
        register(registry, "analyzeTechnicalIndicators", technicalAnalysisTool);
        register(registry, "analyzeFinancialReport", financialAnalysisTool);
        register(registry, "searchStockNewsAndAnnouncements", newsRagTool);
        register(registry, "predictStockTrend", timeSeriesPredictionTool);
        register(registry, "compareStocks", stockComparisonTool);
        register(registry, "analyzePortfolio", portfolioAnalysisTool);
        return registry;
    }

    private void register(Map<String, Object> registry, String name, Object tool) {
        if (tool != null) {
            registry.put(name, tool);
        }
    }

    List<Object> selectTools(Set<String> toolNames) {
        if (toolNames == null || toolNames.isEmpty()) {
            return List.of();
        }
        List<Object> selected = new ArrayList<>();
        for (String toolName : toolNames) {
            Object tool = toolRegistry().get(toolName);
            if (tool != null) {
                selected.add(tool);
            }
        }
        return selected;
    }

    /**
     * 配置股票分析智能体
     */
    @Bean
    public StockAnalysisAssistant stockAnalysisAssistant() {
        log.info("初始化股票分析智能体...");

        return buildAssistant(true);
    }

    /**
     * 无工具规划器：只负责生成候选计划，实际任务和工具权限由后端校验。
     */
    @Bean
    public AgentPlannerAssistant agentPlannerAssistant() {
        log.info("初始化股票分析任务规划器...");
        return AiServices.builder(AgentPlannerAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }

    @Bean
    public QueryRewriteAssistant queryRewriteAssistant() {
        return AiServices.builder(QueryRewriteAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }

    @Bean
    public ConversationSummaryAssistant conversationSummaryAssistant() {
        return AiServices.builder(ConversationSummaryAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .build();
    }

    /**
     * 创建不注册工具的助手，用于请求级别关闭工具调用。
     */
    @Bean
    public StockAnalysisAssistant stockAnalysisAssistantWithoutTools() {
        log.info("初始化无工具股票分析智能体...");
        return buildAssistant(false);
    }

    private StockAnalysisAssistant buildAssistant(boolean enableTools) {
        return buildAssistant(enableTools ? toolRegistry().keySet() : Set.of());
    }

    public StockAnalysisAssistant buildAssistantForTools(Set<String> toolNames) {
        return buildAssistant(toolNames);
    }

    private StockAnalysisAssistant buildAssistant(Set<String> toolNames) {
        var builder = AiServices.builder(StockAnalysisAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .maxSequentialToolsInvocations(agentToolConfig.getMaxSequentialInvocations());

        List<Object> selectedTools = selectTools(toolNames);
        if (!selectedTools.isEmpty()) {
            builder.tools(selectedTools.toArray());
        }

        return builder.build();
    }

}
