package com.ljl.ai.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerQualityGuardTest {

    @Test
    void shouldRejectRealDegeneratedMarkdownSample() {
        String answer = "### 实时行情（来源：Market Data）| | | | | | | | | | | | | | | "
                + ":--- |:--- |:--- |:--- |--|:-|--|:-|--|-||最新价||涨跌幅||MA_||趋势判断||"
                + "||||||||||||||||||||||||||||||||||||||||||||||||||||:``````````````";

        AnswerQualityGuard.Validation validation = new AnswerQualityGuard().validate(answer);

        assertThat(validation.valid()).isFalse();
        assertThat(validation.reason())
                .isEqualTo(AnswerQualityGuard.Reason.EXCESSIVE_MARKDOWN_PUNCTUATION);
    }

    @Test
    void shouldAcceptValidGfmAndCodeBlocks() {
        String answer = """
                ## 数据
                | 指标 | 数值 |
                | --- | ---: |
                | 最新价 | 1500 |

                ```text
                price | volume
                ```
                """;

        assertThat(new AnswerQualityGuard().validate(answer).valid()).isTrue();
    }

    @Test
    void shouldAcceptAnswerEndingWithValidTableRow() {
        String answer = """
                ## 数据

                | 指标 | 数值 |
                | --- | ---: |
                | 最新价 | 1500 |
                """;

        assertThat(new AnswerQualityGuard().validate(answer).valid()).isTrue();
    }

    @Test
    void shouldRejectUnclosedCodeFence() {
        String answer = "## 结论\n\n```text\n未完成的代码块";

        assertThat(new AnswerQualityGuard().validate(answer).reason())
                .isEqualTo(AnswerQualityGuard.Reason.UNCLOSED_CODE_FENCE);
    }

    @Test
    void shouldRejectMismatchedTableColumns() {
        String answer = """
                | 指标 | 数值 |
                | --- | --- |
                | 最新价 | 1500 | 多余列 |
                """;

        assertThat(new AnswerQualityGuard().validate(answer).reason())
                .isEqualTo(AnswerQualityGuard.Reason.INVALID_GFM_TABLE);
    }

    @Test
    void shouldAcceptInlineCodeAndOrdinaryPunctuation() {
        String answer = "使用 `MA_5` 观察趋势；风险提示：波动可能加大。";

        assertThat(new AnswerQualityGuard().validate(answer).valid()).isTrue();
    }
}
