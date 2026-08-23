package com.ljl.ai.agent.agent;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

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


    /**
     * 配置股票分析智能体
     */
    @Bean
    public StockAnalysisAssistant stockAnalysisAssistant() {
        log.info("初始化股票分析智能体...");

        return AiServices.builder(StockAnalysisAssistant.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(chatMemoryProvider)
                .tools(
                        marketDataTool,
                        technicalAnalysisTool,
                        financialAnalysisTool,
                        newsRagTool,
                        timeSeriesPredictionTool,
                        stockComparisonTool,
                        portfolioAnalysisTool
                )
                .build();
    }

}
