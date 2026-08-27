package com.ljl.ai.agent.workflow;

import com.ljl.ai.agent.agent.StockAnalysisAssistant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class WorkflowAnswerGenerator {
    private final StockAnalysisAssistant assistant;

    public WorkflowAnswerGenerator(@Qualifier("stockAnalysisAssistantWithoutTools") StockAnalysisAssistant assistant) {
        this.assistant = assistant;
    }

    public void generate(ExecutionState state, String verifiedResults) {
        String prompt = "问题：" + state.getOriginalQuestion() + "\n\n可信任务结果：\n" + verifiedResults;
        state.setFinalAnswer(assistant.chat(state.getSessionId(), prompt));
    }
}
