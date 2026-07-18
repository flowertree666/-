import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..", "travel-dataset");
const backupRoot = path.join(root, "_incoming_backup", "2026-07-17");

function readJsonl(file) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, "utf8").split(/\r?\n/).filter(Boolean).map((line, index) => {
    try { return JSON.parse(line); }
    catch (error) { throw new Error(`${file}:${index + 1}: ${error.message}`); }
  });
}

function writeJsonl(file, rows) {
  fs.writeFileSync(file, rows.map((row) => JSON.stringify(row)).join("\n") + "\n", "utf8");
}

function unique(rows, key) {
  const values = new Map();
  for (const row of rows) if (row?.[key] && !values.has(row[key])) values.set(row[key], row);
  return [...values.values()];
}

function backupFiles(datasetDir, names) {
  const target = path.join(backupRoot, path.basename(datasetDir));
  fs.mkdirSync(target, { recursive: true });
  for (const name of names) {
    const source = path.join(datasetDir, name);
    const destination = path.join(target, name);
    if (fs.existsSync(source) && !fs.existsSync(destination)) fs.copyFileSync(source, destination);
  }
}

function isoDate(value) {
  if (!value) return null;
  return value.includes("T") ? value : `${value}T00:00:00+08:00`;
}

function sourceFor(sourceIds, sourceIndex) {
  let fallback = null;
  for (const id of sourceIds || []) {
    const source = sourceIndex.get(id);
    if (!source?.source_url) continue;
    fallback ||= source;
    if (source.source_type !== "unverified_placeholder") return source;
  }
  return fallback;
}

function factType(chunk, source) {
  const contentType = chunk.content_type || chunk.fact_type || "unknown";
  if (source?.source_type === "unverified_placeholder") return "unverified_claim";
  if (["dynamic_notice", "temporary_notice"].includes(contentType)) return "temporary_notice";
  if (["evidence_policy", "dynamic_sensitive", "data_governance_rule", "official_recheck_requirement"].includes(contentType)) return "data_governance_rule";
  if (["opening_rule", "ticket_rule", "visitor_service", "transport", "restriction"].includes(contentType)
      && source?.source_type === "official" && source?.confidence !== "low") return "official_fact";
  if (contentType === "official_fact") return "official_fact";
  return "derived_rule";
}

function ticketInfo(poi) {
  if (poi.ticket_info) return poi.ticket_info;
  const min = Number.isFinite(poi.ticket_price_min) ? poi.ticket_price_min : null;
  const max = Number.isFinite(poi.ticket_price_max) ? poi.ticket_price_max : min;
  const price = min == null ? "票价以官方页面为准" : min === max ? `参考票价 ${min} 元/人` : `参考票价 ${min}-${max} 元/人`;
  return [price, poi.reservation_rule].filter(Boolean).join("；");
}

function normalizePoi(poi, auditByPoi = new Map()) {
  const restrictions = Array.isArray(poi.restriction_tags) ? poi.restriction_tags.join(" ") : "";
  const categories = Array.isArray(poi.category) ? poi.category.join(" ") : String(poi.category || "");
  const intensity = String(poi.physical_intensity || "中等");
  const audit = auditByPoi.get(poi.poi_id);
  const verified = !audit || audit.official_direct_rule_page_status === "candidate_found";
  return {
    ...poi,
    city: poi.city,
    aliases: Array.isArray(poi.aliases) ? poi.aliases : [],
    category: Array.isArray(poi.category) ? poi.category : [poi.category || "旅游景点"],
    address: poi.address ?? null,
    coordinate_system: poi.coordinate_system || "GCJ-02",
    amap_poi_id: poi.amap_poi_id || null,
    amap_rating: Number.isFinite(poi.amap_rating) ? poi.amap_rating : null,
    amap_type: poi.amap_type || categories,
    opening_hours: poi.opening_hours || "以官方最新公告为准",
    reservation_required: typeof poi.reservation_required === "boolean" ? poi.reservation_required : null,
    ticket_info: ticketInfo(poi),
    recommended_duration_minutes: poi.recommended_duration_minutes || 120,
    physical_intensity: intensity,
    stairs_or_hiking: typeof poi.stairs_or_hiking === "boolean" ? poi.stairs_or_hiking
      : /登山|徒步|台阶|爬山|高强度|中至高/.test(`${categories} ${restrictions} ${intensity}`),
    best_seasons: Array.isArray(poi.best_seasons) ? poi.best_seasons : [],
    suitable_tags: Array.isArray(poi.suitable_tags) ? poi.suitable_tags : [],
    nearby_cluster: poi.nearby_cluster || `${poi.district || poi.city}片区`,
    data_sources: poi.data_sources || poi.source_ids || [],
    verification_status: verified ? "reviewed_source_available" : "official_rule_url_pending_review",
    verification_note: verified
      ? "静态规则可用于规划，开放、票价、预约与临时公告仍需出发前复核。"
      : "当前主要来源为官网入口或候选规则页面，不能据此断言实时开放、票价或预约状态。",
    collected_at: isoDate(poi.collected_at) || "2026-07-17T00:00:00+08:00",
    updated_at: "2026-07-17T00:00:00+08:00",
  };
}

function normalizeSource(source) {
  let homepageOnly = true;
  try { const parsed = new URL(source.source_url); homepageOnly = !parsed.pathname || parsed.pathname === "/"; }
  catch { /* invalid URLs are downgraded below */ }
  const placeholder = /westlake-(booking-guide|limit)-2026|art_1229512345_59104567/.test(source.source_url || "");
  const normalizedType = placeholder ? "unverified_placeholder"
    : homepageOnly && source.source_type === "official" ? "official_homepage"
    : source.source_type;
  return {
    ...source,
    source_type: normalizedType,
    confidence: placeholder ? "low" : source.confidence,
    verification_note: placeholder ? "URL 疑似占位或经审计返回无效页面，不得作为事实断言来源。"
      : homepageOnly ? "仅指向官网首页，具体开放、票务和预约规则仍需定位直达页面。" : source.verification_note,
    poi_id: source.poi_id || source.related_pois?.[0] || null,
    related_pois: source.related_pois || (source.poi_id ? [source.poi_id] : []),
    facts: source.facts || (source.summary ? [source.summary] : []),
    collected_at: isoDate(source.collected_at),
    published_at: isoDate(source.published_at),
    valid_until: source.valid_until || null,
  };
}

function normalizeChunk(chunk, sourceIndex) {
  const source = sourceFor(chunk.source_ids, sourceIndex);
  const existingUrlIsPlaceholder = /westlake-(booking-guide|limit)-2026|art_1229512345_59104567/.test(chunk.source_url || "");
  return {
    ...chunk,
    fact_type: factType(chunk, source),
    source_url: !existingUrlIsPlaceholder && chunk.source_url ? chunk.source_url : source?.source_url || "",
    published_at: isoDate(chunk.published_at),
    collected_at: isoDate(chunk.collected_at) || "2026-07-17T00:00:00+08:00",
    valid_until: chunk.valid_until || null,
  };
}

function structuralAudit(dataset, pois, chunks, sources, extra = {}) {
  const poiIds = new Set(pois.map((row) => row.poi_id));
  const chunkIds = new Set(chunks.map((row) => row.chunk_id));
  const sourceIds = new Set(sources.map((row) => row.source_id));
  const chunkCountByPoi = Object.fromEntries([...poiIds].map((id) => [id, chunks.filter((chunk) => chunk.poi_id === id).length]));
  const unresolvedSourceIds = [...new Set(chunks.flatMap((chunk) => chunk.source_ids || []).filter((id) => !sourceIds.has(id)))];
  return {
    dataset,
    generated_at: new Date().toISOString(),
    counts: { pois: pois.length, chunks: chunks.length, sources: sources.length },
    duplicate_poi_ids: pois.length - poiIds.size,
    duplicate_chunk_ids: chunks.length - chunkIds.size,
    missing_coordinates: pois.filter((poi) => !Number.isFinite(poi.longitude) || !Number.isFinite(poi.latitude)).map((poi) => poi.poi_id),
    pois_without_chunks: Object.entries(chunkCountByPoi).filter(([, count]) => count === 0).map(([id]) => id),
    minimum_chunks_per_poi: Math.min(...Object.values(chunkCountByPoi)),
    unresolved_source_ids: unresolvedSourceIds,
    unverified_source_ids: sources.filter((source) => source.source_type === "unverified_placeholder").map((source) => source.source_id),
    unverified_chunk_ids: chunks.filter((chunk) => chunk.fact_type === "unverified_claim").map((chunk) => chunk.chunk_id),
    sources_without_specific_path: sources.filter((source) => {
      try { const url = new URL(source.source_url); return url.pathname === "/" || url.pathname === ""; }
      catch { return true; }
    }).map((source) => source.source_id),
    ...extra,
  };
}

function standardizeBeijing() {
  const directory = path.join(root, "beijing");
  const expansion = path.join(directory, "expansion");
  backupFiles(directory, ["pois.jsonl", "knowledge_chunks.jsonl", "sources.jsonl", "claim_evidence.jsonl", "rag_eval_cases.jsonl", "rag_ingestion_manifest.json"]);
  const audits = readJsonl(path.join(expansion, "source_audit.jsonl"));
  const auditByPoi = new Map(audits.map((row) => [row.poi_id, row]));
  const pois = unique([
    ...readJsonl(path.join(directory, "pois.jsonl")),
    ...readJsonl(path.join(expansion, "pois.jsonl")),
  ], "poi_id").map((poi) => normalizePoi(poi, auditByPoi));
  const sources = unique([
    ...readJsonl(path.join(directory, "sources.jsonl")),
    ...readJsonl(path.join(expansion, "sources.jsonl")),
    ...readJsonl(path.join(expansion, "official_rule_sources.jsonl")),
    ...readJsonl(path.join(expansion, "coordinate_sources.jsonl")),
    ...readJsonl(path.join(expansion, "dynamic_status.jsonl")),
  ], "source_id").map(normalizeSource);
  const sourceIndex = new Map(sources.map((source) => [source.source_id, source]));
  const chunks = unique([
    ...readJsonl(path.join(directory, "knowledge_chunks.jsonl")),
    ...readJsonl(path.join(expansion, "knowledge_chunks.jsonl")),
    ...readJsonl(path.join(expansion, "knowledge_chunks_rules.jsonl")),
    ...readJsonl(path.join(expansion, "knowledge_chunks_evidence.jsonl")),
  ], "chunk_id").map((chunk) => normalizeChunk(chunk, sourceIndex));
  writeJsonl(path.join(directory, "pois.jsonl"), pois);
  writeJsonl(path.join(directory, "sources.jsonl"), sources);
  writeJsonl(path.join(directory, "knowledge_chunks.jsonl"), chunks);
  writeJsonl(path.join(directory, "claim_evidence.jsonl"), unique([
    ...readJsonl(path.join(directory, "claim_evidence.jsonl")),
    ...readJsonl(path.join(expansion, "claim_evidence.jsonl")),
  ], "claim_id"));
  writeJsonl(path.join(directory, "rag_eval_cases.jsonl"), unique([
    ...readJsonl(path.join(directory, "rag_eval_cases.jsonl")),
    ...readJsonl(path.join(expansion, "rag_eval_cases.jsonl")),
    ...readJsonl(path.join(expansion, "rag_eval_cases_by_poi.jsonl")),
  ], "eval_id"));
  const report = structuralAudit("beijing", pois, chunks, sources, {
    official_rule_urls_pending_review: audits.filter((row) => row.official_direct_rule_page_status !== "candidate_found").map((row) => row.poi_id),
  });
  fs.writeFileSync(path.join(directory, "standardization_report.json"), JSON.stringify(report, null, 2), "utf8");
  writeManifest(directory, "北京市旅游 RAG 数据集", pois.length, chunks.length, sources.length);
  return report;
}

function standardizeHangzhou() {
  const directory = path.join(root, "hangzhou-data");
  backupFiles(directory, ["pois.jsonl", "knowledge_chunks.jsonl", "sources.jsonl", "claim_evidence.jsonl", "rag_eval_cases.jsonl", "rag_ingestion_manifest.json"]);
  const sources = unique(readJsonl(path.join(directory, "sources.jsonl")), "source_id").map(normalizeSource);
  const sourceIndex = new Map(sources.map((source) => [source.source_id, source]));
  const pois = unique(readJsonl(path.join(directory, "pois.jsonl")), "poi_id").map((poi) => normalizePoi(poi));
  const chunks = unique(readJsonl(path.join(directory, "knowledge_chunks.jsonl")), "chunk_id").map((chunk) => normalizeChunk(chunk, sourceIndex));
  writeJsonl(path.join(directory, "pois.jsonl"), pois);
  writeJsonl(path.join(directory, "sources.jsonl"), sources);
  writeJsonl(path.join(directory, "knowledge_chunks.jsonl"), chunks);
  const placeholderSources = sources.filter((source) => /westlake-(booking-guide|limit)-2026/.test(source.source_url || "")).map((source) => source.source_id);
  const report = structuralAudit("hangzhou", pois, chunks, sources, { placeholder_or_unverified_source_urls: placeholderSources });
  fs.writeFileSync(path.join(directory, "standardization_report.json"), JSON.stringify(report, null, 2), "utf8");
  writeManifest(directory, "杭州市旅游 RAG 数据集", pois.length, chunks.length, sources.length);
  return report;
}

function writeManifest(directory, name, poiCount, chunkCount, sourceCount) {
  const manifest = {
    dataset_name: name,
    dataset_version: "3.0.0-standardized",
    language: "zh-CN",
    poi_count: poiCount,
    chunk_count: chunkCount,
    source_count: sourceCount,
    vector_source_file: "knowledge_chunks.jsonl",
    metadata_source_files: ["pois.jsonl", "sources.jsonl", "claim_evidence.jsonl"],
    required_metadata: ["chunk_id", "city", "poi_id", "fact_type", "source_ids", "source_url", "collected_at", "valid_until"],
    retrieval_policy: {
      mode: "vector_plus_poi_alias_rerank",
      filters: ["city", "poi_id", "valid_until"],
      exclude_fact_types: ["unverified_claim"],
      realtime_answer_policy: "Static chunks cannot confirm current availability, same-day opening, queues or weather-sensitive operations.",
    },
  };
  fs.writeFileSync(path.join(directory, "rag_ingestion_manifest.json"), JSON.stringify(manifest, null, 2), "utf8");
}

const reports = [standardizeBeijing(), standardizeHangzhou()];
console.log(JSON.stringify(reports, null, 2));
