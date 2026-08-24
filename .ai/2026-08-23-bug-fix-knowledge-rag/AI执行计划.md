# AI执行计划 - 知识库与RAG模块Bug修复指导

本文档用于指导AI自动化执行代码修复工作。

---

## 第一部分：代码修复执行步骤

### B001: deleteDocument删除失败导致数据不一致

**任务描述**: 修复KnowledgeService中的deleteDocument方法，实现二阶段删除确保数据一致性。

**执行步骤**:

1. **打开目标文件**
   ```
   文件: src/main/java/com/ljl/ai/agent/knowledge/KnowledgeService.java
   查找方法: deleteDocument(String documentId)
   ```

2. **分析当前代码问题**
   - 现状: 向量删除失败时MongoDB仍会被删除
   - 后果: 孤立向量无法追踪和清理
   - 原因: 无重试机制，异常直接抛出

3. **实现新方法: deleteVectorsWithRetry**
   - 参数: vectorIds (List<String>), documentId (String)
   - 逻辑: 对每个vectorId重试删除，最多3次（指数退避）
   - 失败处理: 记录失败的ID列表，发送告警
   - 返回值: void

4. **修改deleteDocument方法**
   - 步骤1: 标记删除中 (deleteStatus="DELETING")
   - 步骤2: 调用deleteVectorsWithRetry()
   - 步骤3: 删除MongoDB记录
   - 异常处理: catch异常不抛出，记录详细日志

5. **验证修改**
   - 编译: mvn compile检查是否有语法错误
   - 导入: 检查Update类是否导入

---

### B002: processAndStoreDocument无异常处理

**任务描述**: 修复向量存储过程中的segment级异常处理。

**执行步骤**:

1. **打开目标方法**
   ```
   文件: KnowledgeService.java
   方法: processAndStoreDocument(KnowledgeDocument document)
   ```

2. **修改循环逻辑**
   - 原: for(segment) → embed → add(异常直接抛)
   - 新: for(segment) → try embed+add → catch记录失败

3. **实现失败处理**
   - 维护两个列表: successVectorIds, failedSegmentIndices
   - 每次失败记录segment索引
   - 继续处理后续segments

4. **实现成功率判定**
   - 计算: successCount / totalCount
   - 判定: 如果 < 80% 触发补偿
   - 补偿: 删除所有已成功的向量
   - 抛异常: 说明成功率和详细失败原因

5. **改进日志**
   - 记录成功数量和失败数量
   - 记录失败segment的具体错误

6. **验证修改**
   - 编译: mvn compile
   - 逻辑审查: 确保补偿机制完整

---

### B003: syncFeishuDocument并发版本冲突

**任务描述**: 为KnowledgeDocument添加乐观锁支持并修改syncFeishuDocument重试逻辑。

**执行步骤**:

**Part A: 修改实体类**
1. 打开: src/main/java/com/ljl/ai/agent/model/entity/KnowledgeDocument.java
2. 导入: org.springframework.data.annotation.Version
3. 找到version字段 (当前是Integer)
4. 修改为:
   ```java
   @Version
   private Long version;
   ```
5. 添加新字段（删除状态跟踪）:
   ```java
   private String deleteStatus;
   private LocalDateTime deleteTimestamp;
   ```

**Part B: 修改服务方法**
1. 打开: KnowledgeService.java的syncFeishuDocument方法
2. 添加外层while循环: maxRetries = 3
3. 实现重试逻辑:
   - try: 执行文档同步和保存
   - catch OptimisticLockingFailureException: 记录日志，sleep(100*retry)ms，继续循环
   - catch其他异常: 直接抛出

4. 修改版本初始化:
   - 新文档: 不设置version（MongoDB @Version自动管理）
   - 修改version字段: addKnowledgeDocument中改为0L

5. 验证编译

---

### B004: factCheck置信度计算错误

**任务描述**: 修复RagPipelineService中置信度计算逻辑。

**执行步骤**:

1. 打开: src/main/java/com/ljl/ai/agent/rag/RagPipelineService.java
2. 找到: factCheck(String answer, List<RetrievalResult> retrievalResults)
3. 重写逻辑:
   - 如果retrievalResults == null or empty: 返回confidence=0.0
   - 否则计算匹配数
   - 如果matchCount == 0: confidence = 0.3
   - 否则: confidence = (matchCount / size) * 0.7 + 0.3
4. 更新isFactual判定: confidence >= 0.6
5. 添加详细日志说明置信度来源

---

### B005: addDocument无参数验证

**任务描述**: 为KnowledgeController的addDocument添加参数验证。

**执行步骤**:

1. 打开: src/main/java/com/ljl/ai/agent/controller/KnowledgeController.java
2. 导入: org.springframework.util.StringUtils
3. 修改addDocument方法:
   - 变更返回类型: ResponseEntity<?> (而不是ResponseEntity<KnowledgeDocument>)
   - 添加验证逻辑（按顺序）:
     * title非空检查
     * content非空检查
     * title长度 <= 200
     * content大小 <= 10MB
   - 每个验证失败返回: ResponseEntity.badRequest().body(Map)
   - 成功时的响应格式: success=true + data字段
4. 添加try-catch捕获异常，返回500错误

---

### B006: ragQuery无降级方案

**任务描述**: 为RagController的ragQuery添加异常处理和降级方案。

**执行步骤**:

1. 打开: src/main/java/com/ljl/ai/agent/controller/RagController.java
2. 导入: org.springframework.util.StringUtils
3. 修改ragQuery方法:
   - 变更返回类型: ResponseEntity<?> (而不是ResponseEntity<RagResult>)
   - 添加query参数验证 (非空)
   - 将executeRag调用放在try-catch中
   - catch异常:
     * 创建降级返回: 空knowledgeSources、空retrievalResults
     * 返回success=true + warning字段
   - 成功时返回: success=true + data字段

---

## 第二部分：验证和编译

### 编译验证

**执行命令**:
```bash
cd /Users/ljl/code/Agent/ai-stock-agent-langchain4j
mvn clean compile -q
```

**预期结果**:
- 输出: (无，或BUILD SUCCESS)
- 错误数: 0
- 警告数: 0

**如果编译失败**:
1. 检查import语句是否完整
2. 检查方法签名是否正确
3. 检查类型是否匹配 (Integer vs Long, etc)
4. 重新运行compile

---

## 第三部分：代码审查检查清单

修改完成后，AI需要检查以下项目：

### 通用检查
- [ ] 所有新方法有中文注释
- [ ] 所有修改都标记了BUG号 (// BUG B00X修复)
- [ ] import语句完整且无重复
- [ ] 变量命名规范（camelCase）
- [ ] 异常处理完整（try-catch或throws）

### B001检查
- [ ] deleteVectorsWithRetry实现了3次重试
- [ ] 重试间隔使用指数退避
- [ ] 失败ID列表被记录
- [ ] deleteStatus字段被设置

### B002检查
- [ ] successVectorIds和failedSegmentIndices列表被维护
- [ ] 成功率计算正确 (< 80%)
- [ ] 补偿删除被执行
- [ ] 日志记录了失败详情

### B003检查
- [ ] @Version注解被添加到version字段
- [ ] version字段类型是Long
- [ ] 重试循环正确实现
- [ ] OptimisticLockingFailureException被捕获
- [ ] 指数退避实现了sleep

### B004检查
- [ ] 无结果时confidence = 0.0 (而不是0.5)
- [ ] 有结果无匹配时confidence = 0.3
- [ ] 置信度范围在[0.0, 1.0]
- [ ] isFactual阈值是0.6

### B005检查
- [ ] 参数验证按顺序进行
- [ ] 错误返回格式统一 (errorCode + errorMessage)
- [ ] 限制值正确 (title 200字符, content 10MB)
- [ ] 异常被try-catch捕获

### B006检查
- [ ] RAG异常被catch
- [ ] 降级返回有空knowledgeSources
- [ ] warning字段被添加
- [ ] query参数被验证

---

## 第四部分：生成文档

修改完成后，AI需要生成以下文档：

### 1. 设计文档
位置: `ai/2026-08-23-bug-fix-knowledge-rag/设计文档.md`

内容:
- 第一章: 6个bug的详细分析 (包括根本原因和影响范围)
- 第二章: 每个bug的修复方案详解
- 第三章: 核心改进点 (数据一致性、并发控制、容错能力)
- 第四章: 实现路径 (分为4个阶段)
- 第五章: 测试策略
- 第六章: 回滚方案

### 2. 修改文档
位置: `ai/2026-08-23-bug-fix-knowledge-rag/修改文档.md`

内容:
- 每个bug一个section
- 包含: 修改前代码 → 修改后代码
- 新增工具类说明
- 影响范围分析
- 向后兼容性说明

### 3. 执行计划
位置: `ai/2026-08-23-bug-fix-knowledge-rag/执行计划.md`

内容:
- 项目概览（时间、人员、优先级）
- 4个Phase的详细任务分解
- 完整的周粒度时间表
- 资源分配表
- 风险管理清单
- Code Review检查清单
- 故障排查指南

### 4. 实施总结
位置: `ai/2026-08-23-bug-fix-knowledge-rag/实施总结.md`

内容:
- 修复完成情况总结 (✅ or ⏳)
- 修改的文件清单
- 编译验证结果
- 数据库迁移脚本
- 风险评估
- 下一步行动

### 5. README.md
位置: `ai/2026-08-23-bug-fix-knowledge-rag/README.md`

内容:
- 快速开始指南
- 6个bug的修复清单
- 文档导航
- 推荐阅读顺序

### 6. 快速导航
位置: `ai/2026-08-23-bug-fix-knowledge-rag/00-快速导航.txt`

内容:
- 一页纸快速了解项目
- 角色化文档推荐
- 关键数字和时间线
- 常见问题解答

---

## 第五部分：任务完成检查

完成所有修改和文档后，AI需要验证：

### 代码修改完成检查
- [ ] B001: deleteDocument重写完成
- [ ] B002: processAndStoreDocument重写完成
- [ ] B003: syncFeishuDocument重写完成，@Version添加
- [ ] B004: factCheck重写完成
- [ ] B005: addDocument验证逻辑添加
- [ ] B006: ragQuery降级方案添加
- [ ] 所有import语句完整
- [ ] 编译通过 (mvn clean compile -q)

### 文档完成检查
- [ ] 设计文档.md 完成（>500行）
- [ ] 修改文档.md 完成（>500行）
- [ ] 执行计划.md 完成（>500行）
- [ ] 实施总结.md 完成（>200行）
- [ ] README.md 完成（>200行）
- [ ] 00-快速导航.txt 完成

### 最终验证
- [ ] 项目文件夹结构正确
- [ ] 所有6个文档存在
- [ ] 代码编译通过
- [ ] 无编译警告
- [ ] 所有bug都有对应修复

---

## 注意事项

1. **优先级**: B001和B002 (P0) > B003和B004 (P1) > B005和B006 (P2)
2. **依赖关系**: B003需要B005完成后（实体修改）
3. **测试**: 编译通过只是基本验证，真正的测试由人工进行
4. **数据库**: 新增字段需要迁移脚本（见实施总结.md）
5. **发布**: 建议灰度发布而非全量，保留7天回滚窗口

---

**AI执行指导完成。此文档指导AI如何实施所有代码修复和文档生成工作。**

