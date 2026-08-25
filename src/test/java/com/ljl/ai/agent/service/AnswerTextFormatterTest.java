package com.ljl.ai.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AnswerTextFormatterTest {
    @Test
    void shouldTrimAndCollapseBlankLines() {
        assertEquals("标题\n\n正文", AnswerTextFormatter.format("\n 标题  \n\n\n\n正文 \n"));
    }

    @Test
    void shouldPreserveCodeBlockWhitespace() {
        String input = "说明\n\n\n```java\nline1\n\n  line2\n```\n\n\n结论";
        assertEquals("说明\n\n```java\nline1\n\n  line2\n```\n\n结论", AnswerTextFormatter.format(input));
    }
}
