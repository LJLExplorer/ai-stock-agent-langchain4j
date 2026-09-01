package com.ljl.ai;

import com.mongodb.client.MongoClient;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 简单的 MongoDB 连接测试
 * 用于验证 MongoDB 连接是否正常工作
 */
@SpringBootTest(classes = StockAnalysisAgentApplication.class)
public class SimpleMongoConnectionIT {

    @Resource
    private MongoTemplate mongoTemplate;

    @Autowired
    private MongoClient mongoClient;

    /**
     * 测试 MongoDB 连接是否成功
     */
    @Test
    public void testMongoDBConnection() {
        // 验证 MongoTemplate 已注入
        assertNotNull(mongoTemplate, "MongoTemplate 应该被正确注入");

        // 验证 MongoClient 已注入
        assertNotNull(mongoClient, "MongoClient 应该被正确注入");

        // 获取数据库名称
        String databaseName = mongoTemplate.getDb().getName();
        assertNotNull(databaseName, "数据库名称不应为空");

        System.out.println("MongoDB 连接成功！");
        System.out.println("数据库名称: " + databaseName);
    }

    /**
     * 测试 MongoDB 基本读写操作
     */
    @Test
    public void testMongoDBBasicOperation() {
        String collectionName = "mongo_connection_it_" + UUID.randomUUID().toString().replace("-", "");
        try {
            // 创建测试数据
            Map<String, Object> testData = new HashMap<>();
            testData.put("name", "测试用户");
            testData.put("age", 25);
            testData.put("email", "test@example.com");

            // 保存数据
            Map<String, Object> savedData = mongoTemplate.save(testData, collectionName);
            assertNotNull(savedData, "保存的数据不应为空");

            System.out.println("数据保存成功: " + savedData);
        } finally {
            if (mongoTemplate.collectionExists(collectionName)) {
                mongoTemplate.dropCollection(collectionName);
            }
        }
    }

    /**
     * 测试 MongoDB 数据库连接状态
     */
    @Test
    public void testMongoDBServerStatus() {
        try {
            // 执行 ping 命令测试连接
            mongoTemplate.executeCommand("{ ping: 1 }");
            System.out.println("MongoDB 服务器响应正常");
            assertTrue(true, "MongoDB 服务器应该响应 ping 命令");
        } catch (Exception e) {
            fail("MongoDB 连接失败: " + e.getMessage());
        }
    }

    /**
     * 测试获取 MongoDB 集合列表
     */
    @Test
    public void testGetCollectionNames() {
        // 获取所有集合名称
        var collectionNames = mongoTemplate.getCollectionNames();
        assertNotNull(collectionNames, "集合名称列表不应为空");

        System.out.println("当前数据库中的集合:");
        collectionNames.forEach(name -> System.out.println("- " + name));
    }
}
