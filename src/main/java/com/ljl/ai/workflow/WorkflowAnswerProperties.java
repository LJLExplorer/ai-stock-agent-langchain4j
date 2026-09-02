package com.ljl.ai.workflow;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Bounds the verified workflow data provided to the final-answer model. */
@Data
@Component
@ConfigurationProperties(prefix = "workflow.answer")
public class WorkflowAnswerProperties {
    private int maxContextChars = 12_000;
    private int maxTaskChars = 3_500;
    private int maxHistoryEntries = 2;
}
