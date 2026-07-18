import fs from "node:fs/promises";

const base = new URL(".", import.meta.url);
const readJsonl = async (name) =>
  (await fs.readFile(new URL(name, base), "utf8"))
    .split(/\r?\n/)
    .filter(Boolean)
    .map(JSON.parse);
const writeJsonl = async (name, records) =>
  fs.writeFile(new URL(name, base), `${records.map(JSON.stringify).join("\n")}\n`, "utf8");

const pois = await readJsonl("pois.jsonl");
const sources = await readJsonl("sources.jsonl");
const ruleSources = await readJsonl("official_rule_sources.jsonl");
const sourceByPoi = new Map(sources.map((source) => [source.related_pois[0], source]));
const ruleSourceByPoi = new Map(ruleSources.map((source) => [source.related_pois[0], source]));
const collectedAt = "2026-07-16";
const refreshAt = "2026-08-16";

const coordinateSources = pois.map((poi) => ({
  source_id: `BJ-EXP-M-${poi.poi_id.slice(-3)}`,
  city: "北京市",
  related_pois: [poi.poi_id],
  title: `${poi.name}高德POI坐标检索`,
  summary: `用于核验${poi.name}的GCJ-02中心点坐标；中心点不等同于入口、检票口或停车场。`,
  source_url: `https://www.amap.com/search?query=${encodeURIComponent(poi.name)}&city=110000`,
  source_name: "高德地图",
  source_type: "map",
  published_at: null,
  collected_at: collectedAt,
  valid_until: null,
  confidence: "medium",
}));

const dynamicStatus = pois.map((poi) => ({
  source_id: `BJ-EXP-D-${poi.poi_id.slice(-3)}`,
  city: "北京市",
  related_pois: [poi.poi_id],
  title: `${poi.name}动态信息采集状态`,
  summary: `截至${collectedAt}，本轮基础采集未保存具有明确结束日期的${poi.name}官方临时公告。开放、票务、天气、演出或项目运营问题应在${refreshAt}前重新查询官方渠道。`,
  source_url: sourceByPoi.get(poi.poi_id).source_url,
  source_name: sourceByPoi.get(poi.poi_id).source_name,
  source_type: "official_collection_status",
  published_at: null,
  collected_at: collectedAt,
  valid_until: refreshAt,
  confidence: "medium",
}));

const evidenceChunks = pois.map((poi) => {
  const official = sourceByPoi.get(poi.poi_id);
  const ruleSource = ruleSourceByPoi.get(poi.poi_id);
  return {
    chunk_id: `${poi.poi_id}-03`,
    city: "北京市",
    poi_id: poi.poi_id,
    title: `${poi.name}数据时效与证据使用`,
    content: `${poi.name}的基础位置、开放、票务、预约、交通和限制信息应优先引用${official.source_name}。当前数据集记录的是${collectedAt}的基础版本；“今天是否开放”“是否有票”“演出或项目是否运行”“天气是否影响游览”等属于动态问题，必须在回答前重新查询官方来源。高德坐标仅用于空间检索和路线粗排，不能把景区中心点当作实际入口。`,
    tags: ["数据时效", "官方来源", "坐标", "实时核验"],
    source_ids: [official.source_id, ruleSource.source_id, `BJ-EXP-M-${poi.poi_id.slice(-3)}`, `BJ-EXP-D-${poi.poi_id.slice(-3)}`],
    source_type: "official_plus_map",
    content_type: "evidence_policy",
    fact_confidence: "medium",
    collected_at: collectedAt,
    last_verified_at: collectedAt,
    review_after: refreshAt,
    valid_until: refreshAt,
  };
});

const evidence = pois.flatMap((poi) => {
  const official = sourceByPoi.get(poi.poi_id);
  const ruleSource = ruleSourceByPoi.get(poi.poi_id);
  const suffix = poi.poi_id.slice(-3);
  return [
    ["opening_rule", `${poi.name}开放时间：${poi.opening_hours}`, [official.source_id, ruleSource.source_id]],
    ["price", `${poi.name}票价参考范围：${poi.ticket_price_min}-${poi.ticket_price_max}${poi.ticket_price_unit}`, [official.source_id, ruleSource.source_id]],
    ["reservation_rule", `${poi.name}预约要求：${poi.reservation_rule}`, [official.source_id, ruleSource.source_id]],
    ["transport", `${poi.name}交通与入口提示：${poi.transportation}`, [official.source_id, ruleSource.source_id]],
    ["restriction", `${poi.name}限制提示：${poi.restriction_tags.join("、")}`, [official.source_id, ruleSource.source_id]],
    ["coordinates", `${poi.name}GCJ-02中心点：${poi.longitude}, ${poi.latitude}`, [`BJ-EXP-M-${suffix}`]],
    ["dynamic_status", `截至${collectedAt}，未保存具有明确结束日期的${poi.name}官方临时公告；应于${refreshAt}前复核。`, [`BJ-EXP-D-${suffix}`]],
  ].map(([claim_type, claim, source_ids], index) => ({
    claim_id: `${poi.poi_id}-C-${String(index + 1).padStart(2, "0")}`,
    poi_id: poi.poi_id,
    chunk_id: index < 5 ? `${poi.poi_id}-01` : `${poi.poi_id}-03`,
    claim,
    claim_type,
    source_ids,
    confidence: index === 5 ? "medium" : "high",
    valid_until: index === 6 ? refreshAt : null,
    last_verified_at: collectedAt,
  }));
});

const evalCases = pois.map((poi) => ({
  eval_id: `${poi.poi_id}-E-01`,
  poi_id: poi.poi_id,
  question: `${poi.name}今天开放吗？需要预约吗？`,
  expected_features: [
    "识别为实时开放与预约问题",
    `引用${sourceByPoi.get(poi.poi_id).source_name}作为权威来源`,
    "不得仅根据静态数据集断言当天开放或余票",
  ],
  gold_chunk_ids: [`${poi.poi_id}-03`],
  gold_source_ids: [sourceByPoi.get(poi.poi_id).source_id, ruleSourceByPoi.get(poi.poi_id).source_id, `BJ-EXP-D-${poi.poi_id.slice(-3)}`],
  failure_conditions: ["编造当天开放状态", "将景区中心坐标当作实际入口", "遗漏实时核验提示"],
}));

const audit = pois.map((poi) => ({
  poi_id: poi.poi_id,
  name: poi.name,
  official_source_id: sourceByPoi.get(poi.poi_id).source_id,
  official_source_url: sourceByPoi.get(poi.poi_id).source_url,
  official_rule_source_id: ruleSourceByPoi.get(poi.poi_id).source_id,
  official_rule_source_url: ruleSourceByPoi.get(poi.poi_id).source_url,
  coordinate_source_id: `BJ-EXP-M-${poi.poi_id.slice(-3)}`,
  dynamic_status_source_id: `BJ-EXP-D-${poi.poi_id.slice(-3)}`,
  official_direct_rule_page_status: ruleSourceByPoi.get(poi.poi_id).discovery_status,
  note: ruleSourceByPoi.get(poi.poi_id).discovery_status === "candidate_found" ? "已自动发现官网子页；上线前仍应人工确认该页支持的字段。" : "官网入口页未自动发现稳定规则子页；上线前需人工补录开放、票务或预约直达URL。",
}));

await writeJsonl("coordinate_sources.jsonl", coordinateSources);
await writeJsonl("dynamic_status.jsonl", dynamicStatus);
await writeJsonl("knowledge_chunks_evidence.jsonl", evidenceChunks);
await writeJsonl("claim_evidence.jsonl", evidence);
await writeJsonl("rag_eval_cases_by_poi.jsonl", evalCases);
await writeJsonl("source_audit.jsonl", audit);
