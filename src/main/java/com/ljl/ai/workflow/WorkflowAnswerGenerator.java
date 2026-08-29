package com.ljl.ai.workflow;

import com.ljl.ai.agent.StockAnalysisAssistant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class WorkflowAnswerGenerator {
    private final StockAnalysisAssistant assistant;

    public WorkflowAnswerGenerator(@Autowired(required = false)
                                  @Qualifier("stockAnalysisAssistantWithoutTools") StockAnalysisAssistant assistant) {
        this.assistant = assistant;
    }

    public void generate(ExecutionState state, String verifiedResults) {
        if (assistant == null) {
            state.setFinalAnswer("(答案生成器未配置)");
            return;
        }
        String prompt = "问题：" + state.getOriginalQuestion() + "\n\n可信任务结果：\n" + verifiedResults;
        state.setFinalAnswer(assistant.chat(state.getSessionId(), prompt));
    }
}
