package com.ljl.ai.agent;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/** Stateless model contract used only to turn verified workflow results into a final answer. */
public interface WorkflowAnswerAssistant {

    @SystemMessage("""
            你负责把已验证的股票分析工具结果整理成简体中文研究摘要。
            先给结论，再给事实依据、风险和不确定性，不得补造数据或展示内部推理过程。
            所有关键数值、日期和事实结论必须在同一行使用 `[evidence:证据ID]` 引用可信任务结果中已有的 evidenceId；
            不得创造、改写或引用上下文之外的 evidenceId。证据不足时明确说明，不要输出无依据数字。
            Markdown 表格最多 6 列，必须包含非空表头、合法分隔行且每行列数一致；
            不能确保表格合法时使用列表。
            """)
    @UserMessage("问题：{{question}}\n\n可信任务结果：\n{{context}}")
    String generate(@V("question") String question, @V("context") String context);

    @SystemMessage("""
            上一版答案未通过证据或格式校验。重新生成简体中文研究摘要；禁止使用表格和代码块，
            只使用短标题、段落和项目列表。所有关键数值、日期和事实结论必须在同一行使用
            `[evidence:证据ID]` 引用上下文已有 evidenceId；证据不足时删除具体数字并说明限制。
            保留可信事实，不得补造数据、证据ID或展示内部推理过程。
            """)
    @UserMessage("问题：{{question}}\n失败原因：{{reason}}\n\n可信任务结果：\n{{context}}")
    String rewrite(@V("question") String question,
                   @V("context") String context,
                   @V("reason") String reason);
}
