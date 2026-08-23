package com.ljl.ai.agent.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ljl.ai.agent.config.FeishuConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 飞书API客户端 - 用于采集飞书文档
 */
@Slf4j
@Service
public class FeishuClient {

    @Autowired
    private FeishuConfig feishuConfig;
    
    private OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(30,TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();
    
    private String accessToken;
    private LocalDateTime tokenExpireTime;
    
    /**
     * 获取飞书访问令牌
     */
    public String getAccessToken() {
        if (accessToken != null && tokenExpireTime != null && LocalDateTime.now().isBefore(tokenExpireTime)) {
            return accessToken;
        }
        
        try {
            String url = feishuConfig.getBaseUrl() + "/auth/v3/tenant_access_token/internal";
            JSONObject body = new JSONObject();
            body.put("app_id", feishuConfig.getAppId());
            body.put("app_secret", feishuConfig.getAppSecret());
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(body.toJSONString(), MediaType.parse("application/json")))
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getIntValue("code") == 0) {
                        accessToken = result.getString("tenant_access_token");
                        int expire = result.getIntValue("expire");
                        tokenExpireTime = LocalDateTime.now().plusSeconds(expire - 300); // 提前5分钟刷新
                        log.info("获取飞书访问令牌成功");
                        return accessToken;
                    }
                }
            }
        } catch (IOException e) {
            log.error("获取飞书访问令牌失败", e);
        }
        return null;
    }
    
    /**
     * 获取文档内容
     */
    public String getDocumentContent(String docToken) {
        String token = getAccessToken();
        if (token == null) {
            log.error("无法获取飞书访问令牌");
            return null;
        }
        
        try {
            String url = feishuConfig.getBaseUrl() + "/docx/v1/documents/" + docToken + "/raw_content";
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + token)
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getIntValue("code") == 0) {
                        return result.getJSONObject("data").getString("content");
                    } else {
                        log.error("获取文档内容失败: {}", result.getString("msg"));
                    }
                }
            }
        } catch (IOException e) {
            log.error("获取文档内容失败, docToken: {}", docToken, e);
        }
        return null;
    }
    
    /**
     * 获取知识库文档列表
     */
    public JSONObject getWikiDocuments(String spaceId, String parentToken, String pageToken) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }
        
        try {
            StringBuilder urlBuilder = new StringBuilder(feishuConfig.getBaseUrl())
                    .append("/wiki/v2/spaces/")
                    .append(spaceId)
                    .append("/nodes");
            
            if (parentToken != null) {
                urlBuilder.append("?parent_node_token=").append(parentToken);
            }
            if (pageToken != null) {
                urlBuilder.append(parentToken != null ? "&" : "?").append("page_token=").append(pageToken);
            }
            
            Request request = new Request.Builder()
                    .url(urlBuilder.toString())
                    .header("Authorization", "Bearer " + token)
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    return JSON.parseObject(response.body().string());
                }
            }
        } catch (IOException e) {
            log.error("获取知识库文档列表失败", e);
        }
        return null;
    }
    
    /**
     * 获取文档元信息
     */
    public JSONObject getDocumentMeta(String docToken) {
        String token = getAccessToken();
        if (token == null) {
            return null;
        }
        
        try {
            String url = feishuConfig.getBaseUrl() + "/docx/v1/documents/" + docToken;
            
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + token)
                    .get()
                    .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JSONObject result = JSON.parseObject(response.body().string());
                    if (result.getIntValue("code") == 0) {
                        return result.getJSONObject("data").getJSONObject("document");
                    }
                }
            }
        } catch (IOException e) {
            log.error("获取文档元信息失败, docToken: {}", docToken, e);
        }
        return null;
    }
}
