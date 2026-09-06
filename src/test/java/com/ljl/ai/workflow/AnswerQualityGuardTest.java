package com.ljl.ai.workflow;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import static org.assertj.core.api.Assertions.assertThat;

class AnswerQualityGuardTest {

    @Test
    void shouldRejectChineseAndEnglishRepetitiveOutputWithoutMarkdown() {
        AnswerQualityGuard guard = new AnswerQualityGuard();
        assertThat(guard.validate("趋势分析。" + "志同道合".repeat(9)).reason())
                .isEqualTo(AnswerQualityGuard.Reason.REPETITIVE_OUTPUT);
        assertThat(guard.validate("Trend confirmed. ".repeat(10)).reason())
                .isEqualTo(AnswerQualityGuard.Reason.REPETITIVE_OUTPUT);
    }

    @Test
    void shouldRejectRunawayChineseProseEvenWithoutRepeatingPhrases() {
        StringBuilder text = new StringBuilder("GMMA与趋势强度量化评估\n\n");
        for (int codePoint = 0x4e00; codePoint < 0x4e00 + 350; codePoint++) {
            text.appendCodePoint(codePoint);
        }
        assertThat(new AnswerQualityGuard().validate(text.toString()).reason())
                .isEqualTo(AnswerQualityGuard.Reason.RUNAWAY_PROSE);
    }

    @Test
    void shouldAllowNormalFinancialAnalysisWithRepeatedCitations() {
        String text = "价格保持震荡，成交量尚未确认突破。[evidence:ev-price]\n"
                + "均线提供参考，但不能保证未来走势。[evidence:ev-price]\n"
                + "证据不足时不提供评级，也不把指标方法当作已计算的结果。";
        assertThat(new AnswerQualityGuard().validate(text).valid()).isTrue();
    }

    @Test
    void shouldBeRegisteredAsSpringComponent() {
        assertThat(AnswerQualityGuard.class).hasAnnotation(Component.class);
    }

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
