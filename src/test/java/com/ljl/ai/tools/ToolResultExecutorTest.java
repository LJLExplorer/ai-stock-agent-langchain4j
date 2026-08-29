package com.ljl.ai.tools;

import com.ljl.ai.model.dto.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultExecutorTest {
    @Test
    void shouldWrapSuccessfulCallAndRecordCost() {
        ToolResult<String> result = ToolResultExecutor.execute("TEST_ERROR", () -> "ok");

        assertTrue(result.isSuccess());
        assertEquals("ok", result.getData());
        assertTrue(result.getCostTime() >= 0);
    }

    @Test
    void shouldConvertExceptionToFailedResult() {
        ToolResult<String> result = ToolResultExecutor.execute(
                "TEST_ERROR", () -> { throw new IllegalStateException("boom"); });

        assertFalse(result.isSuccess());
        assertEquals("TEST_ERROR", result.getErrorCode());
        assertEquals("boom", result.getErrorMessage());
        assertTrue(result.getCostTime() >= 0);
    }
}
