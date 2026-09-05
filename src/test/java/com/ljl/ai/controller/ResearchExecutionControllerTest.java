package com.ljl.ai.controller;

import com.ljl.ai.model.dto.ResearchExecutionResponse;
import com.ljl.ai.observability.InMemoryRunEventPublisher;
import com.ljl.ai.observability.RunEvent;
import com.ljl.ai.service.ResearchExecutionService;
import com.ljl.ai.workflow.ExecutionState;
import com.ljl.ai.workflow.ExecutionStateStore;
import com.ljl.ai.workflow.WorkflowStatus;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ResearchExecutionControllerTest {

    @Test
    void shouldLoadStatusFromStoreOnlyForItsOwner() {
        ExecutionStateStore store = mock(ExecutionStateStore.class);
        ExecutionState state = ExecutionState.planned("execution-1", "session-1", "分析", List.of());
        state.setUserId("user-1");
        when(store.load("execution-1")).thenReturn(Optional.of(state));
        ResearchExecutionService service = new ResearchExecutionService(
                mock(com.ljl.ai.service.ChatService.class), store, new InMemoryRunEventPublisher());

        try {
            org.assertj.core.api.Assertions.assertThat(service.findOwned("execution-1", "user-1"))
                    .contains(state);
            org.assertj.core.api.Assertions.assertThat(service.findOwned("execution-1", "user-2"))
                    .isEmpty();
        } finally {
            service.close();
        }
    }

    @Test
    void shouldAcceptDeepResearchAsynchronously() throws Exception {
        ResearchExecutionService service = mock(ResearchExecutionService.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        ResearchExecutionResponse accepted = new ResearchExecutionResponse(
                "execution-1", "session-1", ResearchExecutionResponse.Status.ACCEPTED, Instant.now());
        when(service.start(any())).thenReturn(accepted);
        MockMvc mvc = standaloneSetup(new ResearchExecutionController(service, events)).build();

        mvc.perform(post("/api/research/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId":"user-1","message":"深度分析 600519.SH","researchMode":"DEEP"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.executionId").value("execution-1"))
                .andExpect(jsonPath("$.sessionId").value("session-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void shouldReturnOnlyExecutionOwnedByRequestingUser() throws Exception {
        ResearchExecutionService service = mock(ResearchExecutionService.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        ExecutionState state = ExecutionState.planned("execution-1", "session-1", "分析", List.of());
        state.setUserId("user-1");
        when(service.findOwned("execution-1", "user-1")).thenReturn(Optional.of(state));
        when(service.findOwned("execution-1", "user-2")).thenReturn(Optional.empty());
        MockMvc mvc = standaloneSetup(new ResearchExecutionController(service, events)).build();

        mvc.perform(get("/api/research/executions/execution-1").param("userId", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.executionId").value("execution-1"))
                .andExpect(jsonPath("$.userId").value("user-1"));
        mvc.perform(get("/api/research/executions/execution-1").param("userId", "user-2"))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldReplaySnapshotAndCompleteSseAtTerminalEvent() throws Exception {
        ResearchExecutionService service = mock(ResearchExecutionService.class);
        InMemoryRunEventPublisher events = new InMemoryRunEventPublisher();
        ExecutionState state = ExecutionState.planned("execution-1", "session-1", "分析", List.of());
        state.setUserId("user-1");
        state.setWorkflowStatus(WorkflowStatus.COMPLETED);
        when(service.findOwned("execution-1", "user-1")).thenReturn(Optional.of(state));
        events.publish("execution-1", "trace-1", RunEvent.EventType.PLAN_CREATED, "PLAN", "ready");
        events.publish("execution-1", "trace-1", RunEvent.EventType.WORKFLOW_COMPLETED, "ANSWER", "done");
        MockMvc mvc = standaloneSetup(new ResearchExecutionController(service, events)).build();

        MvcResult stream = mvc.perform(get("/api/research/executions/execution-1/events")
                        .param("userId", "user-1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("PLAN_CREATED")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("WORKFLOW_COMPLETED")));
        verify(service).findOwned("execution-1", "user-1");
    }
}
