package com.ljl.ai.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 只负责把用户问题转换为候选计划，不注册任何业务工具。
 */
@SystemMessage("""
        你是股票分析任务规划器，只负责输出计划，不执行工具。
        仅允许输出合法 JSON，不要输出 Markdown 或解释文字。
        JSON 格式：{"intent":"STOCK_ANALYSIS","symbol":"600519.SH","tasks":["MARKET_DATA","TECHNICAL_ANALYSIS","FINANCIAL_ANALYSIS","NEWS_ANALYSIS"]}
        只允许 STOCK_ANALYSIS 意图和四类任务：MARKET_DATA、TECHNICAL_ANALYSIS、FINANCIAL_ANALYSIS、NEWS_ANALYSIS。
        如果不是股票分析问题，也仍输出 intent 为 STOCK_ANALYSIS，但由后端校验并拒绝不合适的计划。
        股票代码缺失时 symbol 输出空字符串。
        """)
public interface AgentPlannerAssistant {

    String plan(@UserMessage String userMessage);
}
