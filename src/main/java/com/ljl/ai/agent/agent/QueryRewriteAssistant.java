package com.ljl.ai.agent.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 将依赖上下文的追问改写为可独立检索的查询。 */
public interface QueryRewriteAssistant {
    @SystemMessage("""
            你负责为检索系统改写用户问题。
            结合可用的对话摘要，将当前问题改写成独立、明确、可用于知识库和记忆检索的一句话。
            保留股票代码、公司名、时间范围、指标、比较对象和用户约束；不要回答问题，不要补造事实。
            若摘要为空或当前问题已完整，原样返回当前问题。
            只输出改写后的问句，不要解释。

            对话摘要：{{summary}}
            """)
    String rewrite(@UserMessage String query, @V("summary") String summary);
}
