package com.ljl.ai.tools;

import com.ljl.ai.client.MarketDataClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ToolContractValidationTest {
    @Test
    void unsupportedTechnicalAndComparisonPeriodsAreRejectedBeforeFetchingData() {
        MarketDataClient client = mock(MarketDataClient.class);
        assertFalse(new TechnicalAnalysisTool(client).analyzeTechnicalIndicators("600519", "1h").isSuccess());
        assertFalse(new StockComparisonTool(client).compareStocks("600519,000001", "1y").isSuccess());
        verifyNoInteractions(client);
    }

    @Test
    void portfolioRejectsFractionalOrNegativeQuantities() {
        MarketDataClient client = mock(MarketDataClient.class);
        PortfolioAnalysisTool tool = new PortfolioAnalysisTool(client);
        assertFalse(tool.analyzePortfolio("[{\"symbol\":\"600519\",\"quantity\":0.5}]").isSuccess());
        assertFalse(tool.analyzePortfolio("[{\"symbol\":\"600519\",\"quantity\":-1}]").isSuccess());
        verifyNoInteractions(client);
    }
}
