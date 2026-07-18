"""Local ChromaDB RAG service for the Smart Travel demo."""

from __future__ import annotations

import hashlib
import json
import math
import os
import re
from collections import Counter
from datetime import date
from pathlib import Path
from typing import Any

import chromadb
from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

ROOT = Path(__file__).resolve().parents[1]
DATASET_ROOT = Path(os.getenv("RAG_DATASET_ROOT", ROOT / "travel-dataset"))
DB_DIR = Path(os.getenv("RAG_CHROMA_DIR", ROOT / "database" / "chroma"))
COLLECTION = "smart_travel_knowledge"
DIMENSION = 512
DEFAULT_CITY = "南京市"

app = FastAPI(title="Smart Travel RAG", version="1.0.0")
client = chromadb.PersistentClient(path=str(DB_DIR))
collection = client.get_or_create_collection(COLLECTION, metadata={"hnsw:space": "cosine"})


class SearchRequest(BaseModel):
    query: str = Field(min_length=2, max_length=1000)
    city: str = DEFAULT_CITY
    target_date: date | None = None
    limit: int = Field(default=6, ge=1, le=15)


@app.get("/", response_class=HTMLResponse)
def home() -> str:
    return """<!doctype html><html lang='zh-CN'><head><meta charset='utf-8'><title>智旅 RAG 服务</title>
<style>body{font-family:Arial,'Microsoft YaHei',sans-serif;max-width:760px;margin:80px auto;color:#18342d;line-height:1.7;padding:0 24px}code{background:#eef4f0;padding:3px 6px;border-radius:4px}a{color:#126b51}</style></head><body>
<h1>智旅 RAG 服务</h1><p>本地 ChromaDB 已启动，用于检索旅行知识片段与运营约束。</p>
<ul><li><a href='/health'>/health</a>：索引状态</li><li><a href='/docs'>/docs</a>：接口文档</li></ul>
<p>检索由 Java 后端统一代理：<code>http://localhost:8080/api/rag/search</code></p></body></html>"""


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    values = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.strip():
            values.append(json.loads(line))
    return values


def dataset_directories() -> list[Path]:
    if not DATASET_ROOT.is_dir():
        return []
    return sorted(
        directory for directory in DATASET_ROOT.iterdir()
        if directory.is_dir()
        and (directory / "pois.jsonl").is_file()
        and (directory / "knowledge_chunks.jsonl").is_file()
    )


def normalize_dataset_city(dataset: Path) -> str:
    for poi in load_jsonl(dataset / "pois.jsonl"):
        city = poi.get("city")
        if isinstance(city, str) and city.strip():
            return city.strip()
    mapping = {
        "nanjing": "南京市",
        "beijing": "北京市",
        "hangzhou-data": "杭州市",
        "hangzhou": "杭州市",
    }
    return mapping.get(dataset.name.lower(), dataset.name)


def parse_day(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return date.fromisoformat(value[:10])
    except ValueError:
        return None


def char_ngrams(text: str) -> list[str]:
    compact = re.sub(r"\s+", "", text.lower())
    chars = [c for c in compact if c.isalnum() or "\u4e00" <= c <= "\u9fff"]
    grams = chars[:]
    grams.extend("".join(chars[index:index + 2]) for index in range(max(0, len(chars) - 1)))
    return grams


def embed(text: str) -> list[float]:
    text = text or ""
    vector = [0.0] * DIMENSION
    for gram, count in Counter(char_ngrams(text)).items():
        slot = int(hashlib.sha256(gram.encode("utf-8")).hexdigest()[:8], 16) % DIMENSION
        vector[slot] += 1.0 + math.log(count)
    norm = math.sqrt(sum(value * value for value in vector))
    return [value / norm for value in vector] if norm else vector


def embedding_text(record: dict[str, Any]) -> str:
    tags = " ".join(str(tag) for tag in record.get("tags", []) if tag)
    return "\n".join(filter(None, [str(record.get("title", "")), str(record.get("content", "")), tags]))


def build_records() -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for dataset in dataset_directories():
        dataset_city = normalize_dataset_city(dataset)
        sources = {
            source.get("source_id"): source
            for source in load_jsonl(dataset / "sources.jsonl")
            if source.get("source_id")
        }
        for record in load_jsonl(dataset / "knowledge_chunks.jsonl"):
            if not record.get("source_url"):
                for source_id in record.get("source_ids", []):
                    source = sources.get(source_id)
                    if source and source.get("source_url"):
                        record["source_url"] = source["source_url"]
                        break
            record.setdefault("fact_type", record.get("content_type", "unknown"))
            record["city"] = dataset_city
            records.append(record)
        for rule in load_jsonl(dataset / "operational_constraints.jsonl"):
            content = rule["content"]
            rule_city = str(rule.get("city") or dataset_city).strip() or dataset_city
            for poi_id in rule["applies_to"]:
                records.append({
                    "chunk_id": f"{rule['constraint_id']}-{poi_id}",
                    "city": rule_city,
                    "poi_id": poi_id,
                    "title": f"运营约束：{rule['rule_type']}",
                    "content": content,
                    "fact_type": "operational_constraint",
                    "source_url": rule["source_url"],
                    "published_at": rule.get("published_at"),
                    "collected_at": rule.get("collected_at"),
                    "valid_until": rule.get("valid_until"),
                })
    return [
        record for record in records
        if isinstance(record.get("content"), str)
        and record["content"].strip()
        and record.get("fact_type") != "unverified_claim"
    ]


def poi_aliases() -> dict[str, list[str]]:
    aliases: dict[str, list[str]] = {}
    suffix_pattern = r"(风景名胜区|风景区|旅游景区|遗址公园|国家地质公园|博物馆|公园)$"
    for dataset in dataset_directories():
        for poi in load_jsonl(dataset / "pois.jsonl"):
            names = [poi.get("name", "")] + list(poi.get("aliases", []))
            expanded = []
            for name in names:
                if not isinstance(name, str) or not name:
                    continue
                expanded.append(name)
                short_name = re.sub(suffix_pattern, "", name)
                if len(short_name) >= 2 and short_name != name:
                    expanded.append(short_name)
            aliases[str(poi.get("poi_id", ""))] = list(dict.fromkeys(expanded))
    return aliases


def compact_metadata(record: dict[str, Any]) -> dict[str, str]:
    return {
        "city": str(record.get("city", DEFAULT_CITY)),
        "poi_id": str(record.get("poi_id", "")),
        "title": str(record.get("title", ""))[:180],
        "fact_type": str(record.get("fact_type", "unknown")),
        "source_url": str(record.get("source_url", "")),
        "published_at": str(record.get("published_at", "") or ""),
        "collected_at": str(record.get("collected_at", "") or ""),
        "valid_until": str(record.get("valid_until", "") or ""),
    }


@app.get("/health")
def health() -> dict[str, Any]:
    return {
        "status": "UP",
        "collection": COLLECTION,
        "documents": collection.count(),
        "embedding_provider": "local-char-ngram-demo",
        "dataset_root": str(DATASET_ROOT),
        "datasets": [directory.name for directory in dataset_directories()],
    }


@app.post("/rebuild")
def rebuild() -> dict[str, Any]:
    records = build_records()
    if not records:
        raise HTTPException(400, "No RAG records found")
    try:
        client.delete_collection(COLLECTION)
    except Exception:
        pass
    global collection
    collection = client.get_or_create_collection(COLLECTION, metadata={"hnsw:space": "cosine"})
    collection.add(
        ids=[record["chunk_id"] for record in records],
        documents=[record["content"] for record in records],
        metadatas=[compact_metadata(record) for record in records],
        embeddings=[embed(embedding_text(record)) for record in records],
    )
    return {
        "indexed": len(records),
        "collection": COLLECTION,
        "datasets": [directory.name for directory in dataset_directories()],
    }


@app.post("/search")
def search(request: SearchRequest) -> dict[str, Any]:
    if collection.count() == 0:
        raise HTTPException(409, "Vector index is empty. Call POST /rebuild first.")
    requested_day = request.target_date or date.today()
    result = collection.query(
        query_embeddings=[embed(request.query)],
        n_results=collection.count(),
        where={"city": request.city},
        include=["documents", "metadatas", "distances"],
    )
    matches = []
    query_text = request.query.replace(" ", "")
    asks_for_governance = any(keyword in query_text for keyword in (
        "来源", "证据", "有效期", "更新", "过期", "可信", "数据",
        "今天", "明天", "现在", "实时", "余票", "排队", "开放", "关闭", "预约", "取消",
        "漂流", "项目", "演出", "展览", "活动", "天气",
    ))
    aliases_by_poi = poi_aliases()
    for chunk_id, document, metadata, distance in zip(
        result["ids"][0], result["documents"][0], result["metadatas"][0], result["distances"][0]
    ):
        expires = parse_day(metadata.get("valid_until"))
        fact_type = metadata.get("fact_type")
        if expires and expires < requested_day and fact_type in {"official_fact", "temporary_notice"}:
            continue
        if fact_type in {"data_governance_rule", "official_recheck_requirement"} and not asks_for_governance:
            continue
        score = max(0.0, 1.0 - float(distance))
        if fact_type in {"data_governance_rule", "official_recheck_requirement"}:
            score += 0.12
        if fact_type == "official_fact":
            score += 0.08
        poi_id = metadata.get("poi_id", "")
        if any(alias.replace(" ", "") in query_text for alias in aliases_by_poi.get(poi_id, [])):
            score += 0.70
        matches.append({
            "chunk_id": chunk_id,
            "content": document,
            "score": round(score, 4),
            "metadata": metadata,
            "expired": bool(expires and expires < requested_day),
        })
    matches.sort(key=lambda item: item["score"], reverse=True)
    matches = matches[:request.limit]
    return {
        "query": request.query,
        "city": request.city,
        "target_date": str(requested_day),
        "matches": matches,
    }


@app.get("/search-get")
def search_get(
    query: str = Query(min_length=2, max_length=1000),
    city: str = DEFAULT_CITY,
    target_date: date | None = None,
    limit: int = Query(default=6, ge=1, le=15),
) -> dict[str, Any]:
    return search(SearchRequest(query=query, city=city, target_date=target_date, limit=limit))
