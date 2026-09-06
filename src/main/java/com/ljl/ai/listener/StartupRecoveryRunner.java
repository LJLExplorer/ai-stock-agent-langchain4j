package com.ljl.ai.listener;

import com.ljl.ai.model.entity.KnowledgeDocument;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.WorkflowStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 单实例部署的启动补偿。在对外就绪和知识回填之前，将上次进程遗留的操作变成可解释、可重试的终态。
 * 不自动重放模型调用，不删除正文或向量。恢复失败则阻止启动，避免静默留下永久运行中的任务。
 */
@Slf4j
@Component
@Order(0)
public class StartupRecoveryRunner implements ApplicationRunner {
    static final String INTERRUPTED_MESSAGE = "服务重启导致研究任务中断，请重新发起研究。";

    private final MongoTemplate mongoTemplate;
    private final LocalDateTime startupTime;

    @Autowired
    public StartupRecoveryRunner(MongoTemplate mongoTemplate) {
        this(mongoTemplate, LocalDateTime.now());
    }

    StartupRecoveryRunner(MongoTemplate mongoTemplate, LocalDateTime startupTime) {
        this.mongoTemplate = mongoTemplate;
        this.startupTime = startupTime;
    }

    @Override
    public void run(ApplicationArguments args) {
        Query interruptedExecutions = Query.query(new Criteria().andOperator(
                Criteria.where("workflowStatus").in(WorkflowStatus.PLANNED, WorkflowStatus.RUNNING,
                        WorkflowStatus.PAUSED, WorkflowStatus.RETRYING),
                beforeStartup("updatedAt")));
        long executions = mongoTemplate.updateMulti(interruptedExecutions, new Update()
                .set("workflowStatus", WorkflowStatus.FAILED)
                .set("errorMessage", INTERRUPTED_MESSAGE)
                .set("updatedAt", startupTime)
                .inc("version", 1), ExecutionState.class).getModifiedCount();

        long deletions = recoverCleanup("DELETING", "DELETE_FAILED", "deleteTimestamp");
        long disables = recoverCleanup("DISABLING", "DISABLE_FAILED", "updateTime");
        log.info("startup_recovery_finished interruptedExecutions={}, retryableDeletions={}, retryableDisables={}",
                executions, deletions, disables);
    }

    private long recoverCleanup(String runningStatus, String failedStatus, String timeField) {
        Query query = Query.query(new Criteria().andOperator(
                Criteria.where("deleteStatus").is(runningStatus), beforeStartup(timeField)));
        return mongoTemplate.updateMulti(query, new Update()
                .set("deleteStatus", failedStatus)
                .set("enabled", false)
                .set("updateTime", startupTime)
                .inc("version", 1), KnowledgeDocument.class).getModifiedCount();
    }

    private Criteria beforeStartup(String field) {
        // 兼容旧数据缺少时间戳；排除本进程启动之后产生或更新的操作。
        return new Criteria().orOperator(Criteria.where(field).lt(startupTime), Criteria.where(field).is(null));
    }
}
