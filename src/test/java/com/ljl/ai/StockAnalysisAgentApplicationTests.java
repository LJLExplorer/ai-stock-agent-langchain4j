package com.ljl.ai;


import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class StockAnalysisAgentApplicationTests {

    @Test
    public void shouldDeclareSpringBootApplicationEntryPoint() {
        assertTrue(StockAnalysisAgentApplication.class.isAnnotationPresent(SpringBootApplication.class));
    }

}
