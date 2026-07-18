# 北京 POI 扩展数据集

采集日期：2026-07-16。该扩展集新增 `BJ-002` 至 `BJ-030` 共 29 个 POI；与故宫 `BJ-001` 数据集分开存放，合并时以 `poi_id` 为唯一键。

## 文件

- `pois.jsonl`：29 条结构化 POI 主记录。
- `sources.jsonl`：29 条景区、博物馆或场馆官方来源记录。
- `official_rule_sources.jsonl`：29 条官网规则子页发现记录；其中已发现 13 条候选直达页，其他条目保留人工补录状态。
- `coordinate_sources.jsonl`：29 条高德 POI 坐标来源记录。
- `dynamic_status.jsonl`：29 条动态信息状态记录，均设置下一次复核截止日。
- `knowledge_chunks.jsonl`：29 条基础游览与路线知识片段。
- `knowledge_chunks_rules.jsonl`：29 条预约、票务、天气或动态核验片段。
- `knowledge_chunks_evidence.jsonl`：29 条来源、时效和坐标使用片段。
- `claim_evidence.jsonl`：203 条事实级证据映射，每个 POI 7 条。
- `rag_eval_cases.jsonl`：10 条跨景点 RAG 评测样例。
- `rag_eval_cases_by_poi.jsonl`：29 条按 POI 覆盖的实时性评测样例。
- `source_audit.jsonl`：来源直达页审计；生产上线前用于补录票务、预约或开放时间的具体官方规则页 URL。

## RAG 导入

向量化时应同时导入三个知识片段文件，共 87 条。检索必须保留 `city`、`poi_id`、`content_type`、`source_ids`、`review_after` 和 `valid_until` 元数据；回答票务、开放、活动、天气、余票和排队问题时，需优先查询对应的官方实时来源，不得从静态片段推断。

## 数据限制

坐标为 GCJ-02 景区中心点，用于城市级检索和粗粒度路线规划，不应当作入口点。票价和开放时间是首轮采集时的参考范围，使用前应以官方页面复核。`source_audit.jsonl` 中的条目表示当前仅保存了官网入口页，尚未捕获稳定的直达规则页 URL；这些条目不得作为生产环境中“已完成来源核验”的数据。
