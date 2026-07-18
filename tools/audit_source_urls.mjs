import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const datasets = ["beijing", "hangzhou-data"];

function loadJsonl(file) {
  return fs.readFileSync(file, "utf8").split(/\r?\n/).filter(Boolean).map(JSON.parse);
}

async function probe(url) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), 8000);
  try {
    const response = await fetch(url, {
      method: "GET",
      redirect: "follow",
      signal: controller.signal,
      headers: { "User-Agent": "SmartTravelDatasetAudit/1.0 (academic validation)" },
    });
    return { reachable: response.status >= 200 && response.status < 400, status: response.status, final_url: response.url };
  } catch (error) {
    return { reachable: false, status: null, error: error.name === "AbortError" ? "timeout" : error.message };
  } finally { clearTimeout(timer); }
}

const rows = [];
for (const dataset of datasets) {
  for (const source of loadJsonl(path.join(root, "travel-dataset", dataset, "sources.jsonl"))) {
    rows.push({ dataset, source_id: source.source_id, source_type: source.source_type, confidence: source.confidence,
      source_url: source.source_url, related_pois: source.related_pois || [] });
  }
}

const uniqueUrls = [...new Set(rows.map((row) => row.source_url).filter(Boolean))];
const probes = new Map();
for (let index = 0; index < uniqueUrls.length; index += 6) {
  const batch = uniqueUrls.slice(index, index + 6);
  const results = await Promise.all(batch.map(probe));
  batch.forEach((url, offset) => probes.set(url, results[offset]));
}

const audited = rows.map((row) => {
  let homepage = true;
  try { const parsed = new URL(row.source_url); homepage = !parsed.pathname || parsed.pathname === "/"; }
  catch { /* invalid URL remains a failure */ }
  const placeholder = /westlake-(booking-guide|limit)-2026/.test(row.source_url || "");
  return { ...row, homepage_only: homepage, placeholder_pattern: placeholder, ...probes.get(row.source_url) };
});
const report = {
  generated_at: new Date().toISOString(),
  note: "HTTP failure can be caused by anti-bot rules or temporary network errors; it is a review signal, not proof that the source is false.",
  summary: {
    source_records: audited.length,
    unique_urls: uniqueUrls.length,
    reachable_records: audited.filter((row) => row.reachable).length,
    unreachable_records: audited.filter((row) => !row.reachable).length,
    homepage_only_records: audited.filter((row) => row.homepage_only).length,
    placeholder_pattern_records: audited.filter((row) => row.placeholder_pattern).length,
  },
  records: audited,
};
fs.writeFileSync(path.join(root, "travel-dataset", "source_url_audit.json"), JSON.stringify(report, null, 2), "utf8");
console.log(JSON.stringify(report.summary, null, 2));
