import fs from "node:fs";
import path from "node:path";

const root = path.resolve(import.meta.dirname, "..");
const api = process.env.API_URL || "http://127.0.0.1:8080/api";
const cases = [
  {
    id: "beijing-route",
    dataset: "beijing",
    request: {
      city: "北京", days: 2, startDate: "2026-07-20",
      preferences: ["历史人文", "博物馆"], pace: "适中", transport: "公共交通", budget: 4000,
      freeText: "必须去故宫，带一名老人，避免连续高强度步行。",
    },
  },
  {
    id: "hangzhou-route",
    dataset: "hangzhou-data",
    request: {
      city: "杭州", days: 2, startDate: "2026-07-20",
      preferences: ["自然风光", "人文古迹", "亲子"], pace: "适中", transport: "公共交通", budget: 3500,
      freeText: "必须去西湖和灵隐寺，带一个孩子，户外景点避开正午高温。",
    },
  },
];

function loadPois(directory) {
  return fs.readFileSync(path.join(root, "travel-dataset", directory, "pois.jsonl"), "utf8")
    .split(/\r?\n/).filter(Boolean).map(JSON.parse);
}

function normalize(name) {
  return String(name || "").replace(/\s+/g, "").replace(/(旅游景区|风景名胜区|风景区|景区)$/g, "");
}

const reports = [];
for (const testCase of cases) {
  const pois = loadPois(testCase.dataset);
  const acceptedNames = new Set(pois.flatMap((poi) => [poi.name, ...(poi.aliases || [])]).map(normalize));
  const response = await fetch(`${api}/plans`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(testCase.request),
  });
  const body = await response.json();
  if (!response.ok) throw new Error(`${testCase.id}: ${body.message || response.status}`);
  const stops = body.plans.flatMap((plan) => plan.days.flatMap((day) => day.stops));
  const unmatched = stops.filter((stop) => !acceptedNames.has(normalize(stop.name))).map((stop) => stop.name);
  const uniqueNames = [...new Set(stops.map((stop) => stop.name))];
  reports.push({
    id: testCase.id,
    canonical_city: body.profile.city,
    plans: body.plans.length,
    total_stops_across_plans: stops.length,
    unique_stop_names: uniqueNames,
    dataset_stop_hit_rate_percent: Math.round((stops.length - unmatched.length) / stops.length * 10000) / 100,
    unmatched_stop_names: [...new Set(unmatched)],
    ai_status: body.ai?.status,
  });
}

fs.writeFileSync(path.join(root, "travel-dataset", "route_integration_report.json"), JSON.stringify({ generated_at: new Date().toISOString(), reports }, null, 2), "utf8");
console.log(JSON.stringify(reports, null, 2));
