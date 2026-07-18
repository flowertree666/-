import fs from "node:fs/promises";

const base = new URL(".", import.meta.url);
const rows = (await fs.readFile(new URL("sources.jsonl", base), "utf8"))
  .split(/\r?\n/)
  .filter(Boolean)
  .map(JSON.parse);
const keywords = /开放|票务|门票|预约|参观|游园|服务|导览|指南/;
const strip = (value) => value.replace(/<[^>]+>/g, " ").replace(/\s+/g, " ").trim();
const entries = [];

for (const source of rows) {
  try {
    const response = await fetch(source.source_url, {
      headers: { "user-agent": "Mozilla/5.0", "accept-language": "zh-CN,zh;q=0.9" },
      signal: AbortSignal.timeout(15000),
    });
    const html = await response.text();
    const candidates = [...html.matchAll(/<a\b[^>]*href=["']([^"'#]+)["'][^>]*>([\s\S]*?)<\/a>/gi)]
      .map((match) => ({ href: new URL(match[1], source.source_url).href, label: strip(match[2]) }))
      .filter((item) => item.label && keywords.test(item.label))
      .filter((item) => /^https?:/i.test(item.href));
    const chosen = candidates.find((item) => new URL(item.href).hostname === new URL(source.source_url).hostname) ?? candidates[0];
    entries.push({
      source_id: `BJ-EXP-O-${source.related_pois[0].slice(-3)}`,
      city: "北京市",
      related_pois: source.related_pois,
      title: chosen ? `${source.related_pois[0]}官方规则页：${chosen.label}` : `${source.related_pois[0]}官方规则页待补录`,
      summary: chosen ? `从${source.source_name}入口页发现的规则/服务子页。使用前仍需人工确认页面具体支持的字段。` : "官网入口页未自动发现稳定的开放、票务或预约子页，需人工补录。",
      source_url: chosen?.href ?? source.source_url,
      source_name: source.source_name,
      source_type: chosen ? "official_rule_page_candidate" : "official_rule_page_pending",
      published_at: null,
      collected_at: "2026-07-16",
      valid_until: null,
      confidence: chosen ? "medium" : "low",
      discovery_status: chosen ? "candidate_found" : "pending_manual_url_capture",
    });
  } catch (error) {
    entries.push({
      source_id: `BJ-EXP-O-${source.related_pois[0].slice(-3)}`,
      city: "北京市",
      related_pois: source.related_pois,
      title: `${source.related_pois[0]}官方规则页待补录`,
      summary: `采集官网入口页时失败，需人工补录具体的开放、票务或预约规则页。错误类型：${error.name}。`,
      source_url: source.source_url,
      source_name: source.source_name,
      source_type: "official_rule_page_pending",
      published_at: null,
      collected_at: "2026-07-16",
      valid_until: null,
      confidence: "low",
      discovery_status: "fetch_failed",
    });
  }
}

await fs.writeFile(new URL("official_rule_sources.jsonl", base), `${entries.map(JSON.stringify).join("\n")}\n`, "utf8");
