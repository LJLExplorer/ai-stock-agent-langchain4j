package com.ljl.ai.workflow;

import org.springframework.stereotype.Component;

/** 将 Reflector 的复盘结果收敛为受限的图路由。 */
@Component
public class WorkflowCritic {

    public Decision criticize(WorkflowReflector.ReflectionDecision reflection) {
        if (reflection.trusted()) {
            return new Decision(Route.ANSWER, reflection.reason());
        }
        if (!reflection.retryTaskIds().isEmpty()) {
            return new Decision(Route.RETRY, reflection.reason());
        }
        if (!reflection.additionalTasks().isEmpty()) {
            return new Decision(Route.ADD_NEWS, reflection.reason());
        }
        return new Decision(Route.FAILED, reflection.reason());
    }

    public enum Route {
        RETRY,
        ADD_NEWS,
        ANSWER,
        FAILED
    }

    public record Decision(Route route, String reason) {
    }
}
