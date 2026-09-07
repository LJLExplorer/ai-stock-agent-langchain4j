package com.ljl.ai.controller;

import com.ljl.ai.rag.RagPipelineService;
import com.ljl.ai.rag.RetrievalService;
import com.ljl.ai.service.ChatService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class RequestValidationTest {
    @Test
    void invalidFeedbackAndNullTitleAreClientErrors() throws Exception {
        ChatService service = mock(ChatService.class);
        MockMvc mvc = standaloneSetup(new ChatController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String value : new String[]{"0.5", "4294967296", "-1.5", "null", "\"1\""}) {
            mvc.perform(post("/api/chat/messages/message/feedback").contentType("application/json")
                            .content("{\"feedback\":" + value + "}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorMessage").isString());
        }
        mvc.perform(patch("/api/chat/sessions/session/title").param("userId", "user")
                        .contentType("application/json").content("{\"title\":null}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorMessage").isString());
        verifyNoInteractions(service);
    }

    @Test
    void invalidQueryAndMalformedJsonDoNotBecomeServerErrors() throws Exception {
        RagPipelineService pipeline = mock(RagPipelineService.class);
        MockMvc mvc = standaloneSetup(new RagController(mock(RetrievalService.class), pipeline))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String body : new String[]{"{\"query\":123}", "{\"query\":{}}", "{\"query\":null}", "{broken"}) {
            mvc.perform(post("/api/rag/query").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errorMessage").isString());
        }
        verifyNoInteractions(pipeline);
    }
}
