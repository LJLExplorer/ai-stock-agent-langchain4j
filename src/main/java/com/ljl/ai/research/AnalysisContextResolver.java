package com.ljl.ai.research;

import com.ljl.ai.model.dto.ChatRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将兼容的聊天请求统一解析成投研上下文。
 */
@Component
public class AnalysisContextResolver {

    private static final Pattern STOCK_SYMBOL = Pattern.compile("(?<!\\d)(\\d{6}(?:\\.(?:SH|SZ|BJ))?)(?!\\d)",
            Pattern.CASE_INSENSITIVE);

    private final Clock clock;

    public AnalysisContextResolver() {
        this(Clock.systemDefaultZone());
    }

    AnalysisContextResolver(Clock clock) {
        this.clock = clock;
    }

    public AnalysisContext resolve(ChatRequest request, String sessionId, String executionId, String traceId) {
        LocalDate today = LocalDate.now(clock);
        LocalDate analysisDate = request.getAnalysisDate() == null ? today : request.getAnalysisDate();
        if (analysisDate.isAfter(today)) {
            throw new IllegalArgumentException("analysisDate 不能晚于当前日期");
        }

        return new AnalysisContext(
                resolveSymbol(request),
                analysisDate,
                request.getResearchMode(),
                executionId,
                traceId,
                request.getUserId(),
                sessionId
        );
    }

    private String resolveSymbol(ChatRequest request) {
        if (StringUtils.isNotBlank(request.getOrderId())) {
            return request.getOrderId().trim().toUpperCase(Locale.ROOT);
        }
        Matcher matcher = STOCK_SYMBOL.matcher(StringUtils.defaultString(request.getMessage()));
        return matcher.find() ? matcher.group(1).toUpperCase(Locale.ROOT) : null;
    }
}
