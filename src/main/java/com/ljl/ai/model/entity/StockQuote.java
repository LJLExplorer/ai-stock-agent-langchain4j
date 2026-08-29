package com.ljl.ai.model.entity;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class StockQuote {
    private String symbol;
    private String name;
    private BigDecimal price;
    private BigDecimal changePercent;
    private Long volume;
    private BigDecimal turnoverRate;
    private LocalDateTime timestamp;
}
