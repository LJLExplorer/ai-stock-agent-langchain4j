package com.ljl.ai.listener;

import com.ljl.ai.model.entity.KnowledgeDocument;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.WorkflowStatus;
import com.mongodb.client.result.UpdateResult;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartupRecoveryRunnerTest {
    private final LocalDateTime startupTime = LocalDateTime.of(2026, 9, 7, 12, 0);

    @Test
    void recoveryQueriesExcludeTerminalAndPostStartupExecutionsAndPreserveCheckpoints() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        List<Query> queries = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(ExecutionState.class)))
                .thenAnswer(invocation -> {
                    queries.add(invocation.getArgument(0));
                    updates.add(invocation.getArgument(1));
                    return UpdateResult.acknowledged(2, 2L, null);
                });
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(KnowledgeDocument.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));

        new StartupRecoveryRunner(mongo, startupTime).run(null);

        List<Document> conditions = queries.getFirst().getQueryObject().getList("$and", Document.class);
        assertThat(conditions.getFirst().get("workflowStatus", Document.class).getList("$in", WorkflowStatus.class))
                .containsExactly(WorkflowStatus.PLANNED, WorkflowStatus.RUNNING, WorkflowStatus.PAUSED,
                        WorkflowStatus.RETRYING);
        assertBeforeStartup(conditions.getLast(), "updatedAt");
        Document update = updates.getFirst().getUpdateObject();
        assertThat(update.get("$set", Document.class)).containsExactlyInAnyOrderEntriesOf(new Document()
                .append("workflowStatus", WorkflowStatus.FAILED)
                .append("errorMessage", StartupRecoveryRunner.INTERRUPTED_MESSAGE)
                .append("updatedAt", startupTime));
        assertThat(update.get("$inc", Document.class)).containsEntry("version", 1);
        // 不重写 plan/tasks/finalAnswer，也不重置 eventSequence。
        assertThat(update.keySet()).containsExactlyInAnyOrder("$set", "$inc");
    }

    @Test
    void interruptedCleanupBecomesRetryableWithoutDeletingContentOrEnablingRetrieval() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(ExecutionState.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        List<Query> queries = new ArrayList<>();
        List<Update> updates = new ArrayList<>();
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(KnowledgeDocument.class)))
                .thenAnswer(invocation -> {
                    queries.add(invocation.getArgument(0));
                    updates.add(invocation.getArgument(1));
                    return UpdateResult.acknowledged(1, 1L, null);
                });

        new StartupRecoveryRunner(mongo, startupTime).run(null);

        for (int i = 0; i < 2; i++) {
            List<Document> conditions = queries.get(i).getQueryObject().getList("$and", Document.class);
            assertThat(conditions.getFirst()).containsEntry("deleteStatus", i == 0 ? "DELETING" : "DISABLING");
            assertBeforeStartup(conditions.getLast(), i == 0 ? "deleteTimestamp" : "updateTime");
            Document update = updates.get(i).getUpdateObject();
            assertThat(update.get("$set", Document.class)).containsExactlyInAnyOrderEntriesOf(new Document()
                    .append("deleteStatus", i == 0 ? "DELETE_FAILED" : "DISABLE_FAILED")
                    .append("enabled", false).append("updateTime", startupTime));
            assertThat(update.get("$inc", Document.class)).containsEntry("version", 1);
            assertThat(update.keySet()).containsExactlyInAnyOrder("$set", "$inc");
        }
    }

    @Test
    void databaseFailureMustNotBeSilentlyIgnoredAndRecoveryCanBeRetried() {
        MongoTemplate mongo = mock(MongoTemplate.class);
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(ExecutionState.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        when(mongo.updateMulti(any(Query.class), any(Update.class), eq(KnowledgeDocument.class)))
                .thenThrow(new IllegalStateException("database unavailable"))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        StartupRecoveryRunner runner = new StartupRecoveryRunner(mongo, startupTime);

        assertThrows(IllegalStateException.class, () -> runner.run(null));
        runner.run(null);

        verify(mongo, org.mockito.Mockito.times(2))
                .updateMulti(any(Query.class), any(Update.class), eq(ExecutionState.class));
    }

    private void assertBeforeStartup(Document condition, String field) {
        assertThat(condition.getList("$or", Document.class)).containsExactly(
                new Document(field, new Document("$lt", startupTime)), new Document(field, null));
    }
}
