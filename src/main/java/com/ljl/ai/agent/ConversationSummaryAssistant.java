package com.ljl.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 将已压缩历史和本次淘汰的消息递归整理为受限长度的会话摘要。 */
public interface ConversationSummaryAssistant {

    @SystemMessage("""
            你负责维护一段可持续更新的对话记忆摘要。
            将已有摘要与本次淘汰的较早对话合并为新的中文摘要。保留用户身份和偏好、已确认的股票/时间范围/约束、关键事实和结论、用户纠正的信息、未完成事项；删除寒暄、重复和模型的无关表述。
            不要编造未出现的事实，不要解释过程，不要使用 Markdown 标题。输出必须不超过 {{maxChars}} 个字符。

            已有摘要：
            {{previousSummary}}
            """)
    String summarize(@UserMessage String evictedMessages,
                     @V("previousSummary") String previousSummary,
                     @V("maxChars") int maxChars);
}
