package com.ljl.ai.tools;

import com.ljl.ai.model.dto.ToolResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultTest {
    @Test
    void shouldBuildSuccessfulResult() {
        ToolResult<String> result = ToolResult.success("quote");

        assertTrue(result.isSuccess());
        assertEquals("quote", result.getData());
        assertNull(result.getErrorCode());
        assertNull(result.getErrorMessage());
    }

    @Test
    void shouldBuildFailedResult() {
        ToolResult<String> result = ToolResult.failure("QUOTE_ERROR", "行情服务不可用");

        assertFalse(result.isSuccess());
        assertNull(result.getData());
        assertEquals("QUOTE_ERROR", result.getErrorCode());
        assertEquals("行情服务不可用", result.getErrorMessage());
    }
}
