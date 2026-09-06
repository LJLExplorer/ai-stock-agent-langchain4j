package com.ljl.ai.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用才使用本地配置联网；只验证新闻/官方来源，不调用 LLM、不写业务数据库。 */
@EnabledIfSystemProperty(named = "news.live", matches = "true")
class NewsSearchLiveTest {
    @Test
    void shouldRetrieveVerifiableSourcesBeyondTencentQuotePage() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("local", new FileSystemResource("src/main/resources/application.yml"))
                .forEach(source -> environment.getPropertySources().addLast(source));
        NewsSearchClient client = new NewsSearchClient();
        ReflectionTestUtils.setField(client, "configuredTavilyKey", environment.getProperty("news-search.tavily-api-key", ""));
        ReflectionTestUtils.setField(client, "configuredSerpApiKey", environment.getProperty("news-search.serpapi-api-key", ""));
        ReflectionTestUtils.setField(client, "marketDataClient", new MarketDataClient());
        var results = client.search("600519.SH", "公司官网公告 财报 新闻", 30, 5, LocalDate.now());
        assertThat(results).isNotEmpty();
        for (var item : results) {
            System.out.printf("SOURCE title=%s publishedAt=%s url=%s%n", item.title(), item.publishedAt(), item.url());
            assertThat(item.url()).doesNotContain("gu.qq.com/sh600519/gp", "github.com", "skills.sh");
            assertThat(item.temporalStatus().name()).isEqualTo("VERIFIED");
        }
        assertThat(results.stream().map(item -> java.net.URI.create(item.url()).getHost()).toList()).anySatisfy(host -> assertThat(host)
                .matches("(?:.*\\.)?(?:moutai.com.cn|moutaichina.com|sse.com.cn|cninfo.com.cn|szse.cn|bse.cn)"));
    }
}
