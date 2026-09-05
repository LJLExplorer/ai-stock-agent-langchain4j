package com.ljl.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** 无工具、无会话记忆的固定角色研究契约。 */
public interface DeepResearchAssistant {
    String ROLE_RULES = """
            你是受控股票投研 DAG 中的单一角色。只能使用给定证据和上游摘要；不得调用工具、补造事实、
            输出内部推理过程或引用不存在的 evidenceId。输出不超过 1200 个汉字，并显式保留证据ID。
            """;

    @SystemMessage(ROLE_RULES + "\n角色：基本面分析师。聚焦财务质量、盈利能力和估值证据。")
    @UserMessage("证据包：\n{{evidence}}\n\n上游摘要：\n{{upstream}}")
    String fundamental(@V("evidence") String evidence, @V("upstream") String upstream);

    @SystemMessage(ROLE_RULES + "\n角色：技术面分析师。聚焦价格、趋势和技术证据。")
    @UserMessage("证据包：\n{{evidence}}\n\n上游摘要：\n{{upstream}}")
    String technical(@V("evidence") String evidence, @V("upstream") String upstream);

    @SystemMessage(ROLE_RULES + "\n角色：新闻分析师。聚焦新闻、公告、来源时间和事件影响。")
    @UserMessage("证据包：\n{{evidence}}\n\n上游摘要：\n{{upstream}}")
    String news(@V("evidence") String evidence, @V("upstream") String upstream);

    @SystemMessage(ROLE_RULES + "\n角色：看多研究员。只提出证据可支持的上行情景。")
    @UserMessage("证据包：\n{{evidence}}\n\n上游摘要：\n{{upstream}}")
    String bull(@V("evidence") String evidence, @V("upstream") String upstream);

    @SystemMessage(ROLE_RULES + "\n角色：看空研究员。只提出证据可支持的下行情景。")
    @UserMessage("证据包：\n{{evidence}}\n\n上游摘要：\n{{upstream}}")
    String bear(@V("evidence") String evidence, @V("upstream") String upstream);

    @SystemMessage(ROLE_RULES + "\n角色：风险官。识别证据缺口、时效风险和相互冲突。")
    @UserMessage("证据包：\n{{evidence}}\n\n上游摘要：\n{{upstream}}")
    String risk(@V("evidence") String evidence, @V("upstream") String upstream);

    @SystemMessage(ROLE_RULES + """

            角色：裁决者。综合各角色输出，只返回一个 JSON 对象，不使用 Markdown：
            {"rating":"BULLISH|NEUTRAL|BEARISH|INSUFFICIENT_DATA","confidence":0到1,
            "summary":"结论","evidenceIds":["ev-..."],"risks":["风险"],"dataAsOf":"YYYY-MM-DD"}
            """)
    @UserMessage("证据包：\n{{evidence}}\n\n角色摘要：\n{{upstream}}")
    String judge(@V("evidence") String evidence, @V("upstream") String upstream);
}
