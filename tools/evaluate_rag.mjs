import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const service = process.env.RAG_URL || "http://127.0.0.1:8091";
const configurations = [
  { name: "beijing", directory: "beijing", city: "北京市" },
  { name: "hangzhou", directory: "hangzhou-data", city: "杭州市" },
];

function loadJsonl(file) {
  if (!fs.existsSync(file)) return [];
  return fs.readFileSync(file, "utf8").split(/\r?\n/).filter(Boolean).map(JSON.parse);
}

function round(value) { return Math.round(value * 10000) / 100; }

async function evaluate(configuration) {
  const directory = path.join(root, "travel-dataset", configuration.directory);
  const chunks = loadJsonl(path.join(directory, "knowledge_chunks.jsonl"));
  const sources = loadJsonl(path.join(directory, "sources.jsonl"));
  const cases = loadJsonl(path.join(directory, "rag_eval_cases.jsonl"));
  const chunkById = new Map(chunks.map((chunk) => [chunk.chunk_id, chunk]));
  const sourceById = new Map(sources.map((source) => [source.source_id, source]));
  const details = [];

  for (const testCase of cases) {
    const expectedChunkIds = new Set(testCase.gold_chunk_ids || []);
    const trustedGoldSources = (testCase.gold_source_ids || []).filter((sourceId) => sourceById.get(sourceId)?.source_type !== "unverified_placeholder");
    for (const sourceId of testCase.gold_source_ids || []) {
      for (const chunk of chunks) if ((chunk.source_ids || []).includes(sourceId)) expectedChunkIds.add(chunk.chunk_id);
    }
    const expectedPoiIds = new Set(testCase.poi_id ? [testCase.poi_id] : []);
    for (const chunkId of expectedChunkIds) {
      const poiId = chunkById.get(chunkId)?.poi_id;
      if (poiId) expectedPoiIds.add(poiId);
    }
    for (const sourceId of testCase.gold_source_ids || []) {
      const source = sourceById.get(sourceId);
      for (const poiId of source?.related_pois || []) expectedPoiIds.add(poiId);
      if (source?.poi_id) expectedPoiIds.add(source.poi_id);
    }
    if (!expectedChunkIds.size && !expectedPoiIds.size) continue;

    const url = new URL("/search-get", service);
    url.searchParams.set("query", testCase.question);
    url.searchParams.set("city", configuration.city);
    url.searchParams.set("target_date", "2026-07-17");
    url.searchParams.set("limit", "5");
    const response = await fetch(url);
    if (!response.ok) throw new Error(`${configuration.name}/${testCase.eval_id}: HTTP ${response.status}`);
    const body = await response.json();
    const returnedChunks = body.matches.map((match) => match.chunk_id);
    const returnedPois = body.matches.map((match) => match.metadata.poi_id).filter(Boolean);
    details.push({
      eval_id: testCase.eval_id,
      question: testCase.question,
      top1_chunk_hit: expectedChunkIds.has(returnedChunks[0]),
      top5_chunk_hit: returnedChunks.some((id) => expectedChunkIds.has(id)),
      top5_poi_hit: returnedPois.some((id) => expectedPoiIds.has(id)),
      city_isolated: body.matches.every((match) => match.metadata.city === configuration.city),
      returned_chunk_ids: returnedChunks,
      expected_chunk_ids: [...expectedChunkIds],
      expected_poi_ids: [...expectedPoiIds],
      trusted_case: !(testCase.gold_source_ids || []).length || trustedGoldSources.length > 0,
    });
  }

  const count = details.length;
  const trusted = details.filter((row) => row.trusted_case);
  return {
    dataset: configuration.name,
    evaluated_cases: count,
    top1_chunk_accuracy_percent: round(details.filter((row) => row.top1_chunk_hit).length / count),
    top5_chunk_recall_percent: round(details.filter((row) => row.top5_chunk_hit).length / count),
    top5_poi_hit_rate_percent: round(details.filter((row) => row.top5_poi_hit).length / count),
    city_isolation_percent: round(details.filter((row) => row.city_isolated).length / count),
    trusted_cases: trusted.length,
    trusted_top5_chunk_recall_percent: round(trusted.filter((row) => row.top5_chunk_hit).length / trusted.length),
    failed_case_ids: details.filter((row) => !row.top5_chunk_hit).map((row) => row.eval_id),
    details,
  };
}

const reports = [];
for (const configuration of configurations) reports.push(await evaluate(configuration));
const output = {
  generated_at: new Date().toISOString(),
  service,
  metric_definition: {
    top1_chunk_accuracy: "第一条结果命中评测样例标注的 gold chunk，或命中其 gold source 对应片段。",
    top5_chunk_recall: "前五条结果至少一条命中 gold chunk。",
    top5_poi_hit_rate: "前五条结果至少一条属于预期 POI。",
    city_isolation: "结果元数据全部属于查询城市。",
  },
  reports,
};
fs.writeFileSync(path.join(root, "travel-dataset", "rag_evaluation_report.json"), JSON.stringify(output, null, 2), "utf8");
console.log(JSON.stringify(reports.map(({ details, ...summary }) => summary), null, 2));
