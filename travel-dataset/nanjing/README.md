# 南京旅游数据集

本目录保存南京市旅游数据的原始证据、结构化 POI 与 RAG 知识片段。

- `pois.jsonl`：每行一条景点主数据。
- `knowledge_chunks.jsonl`：每行一条可向量化的知识片段。
- `sources.jsonl`：每行一条可核验的具体网页来源。
- `poi_assets/NJ-001/`：中山陵的高德原始响应、官方网页快照和人工记录。

JSONL 文件目前为空。采集数据时使用 UTF-8 编码，每条记录独占一行；不得在其中写入 API 密钥或登录 Cookie。
