# 变更记录

## 2026-09-04

- 确认采用自定义四级层级切分器：标题/章节、中文段落、中文句子、字符兜底。
- 确认 Child 目标长度 600～800 字符，Overlap 80～120 字符，且不跨 Parent Section。
- 确认 Child 完整继承 headingPath、stockCode、year 和 tags。
- 确认 Parent Section 使用独立 MongoDB 集合持久化全文、摘要和 Child 原文区间。
- 确认 Parent 不超过 1200 字符时返回全文；超过时返回 400～600 字符抽取式摘要。
- 确认摘要不调用 LLM，且不参与 Child Embedding。
- 确认命中 Child 后补充同 Parent 内前后各一个 Chunk，重叠窗口合并去重并按 chunkIndex 拼接。
- 确认使用 ingestion version 保证同步、回填、禁用和删除过程的一致性。
