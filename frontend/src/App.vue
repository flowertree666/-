<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import RouteMap from "./components/RouteMap.vue";

const api = import.meta.env.VITE_API_BASE || `${window.location.protocol}//${window.location.hostname}:8080/api`;
const preferences = ["自然风光", "人文古迹", "博物馆", "城市漫游", "亲子", "摄影", "美食"];
const form = ref({
  city: "杭州",
  days: 3,
  startDate: new Date().toISOString().slice(0, 10),
  preferences: ["自然风光", "人文古迹"],
  pace: "适中",
  transport: "公共交通",
  budget: 3000,
  freeText: "",
});

const result = ref(null);
const activePlanIndex = ref(0);
const activeDayIndex = ref(0);
const loading = ref(false);
const error = ref("");
const destinationError = ref("");
const histories = ref([]);
const historyOpen = ref(false);
const ragStatus = ref(null);
const ragEvidence = ref([]);
const evidenceExpanded = ref(false);
let trafficTimer;

const activePlan = computed(() => result.value?.plans?.[activePlanIndex.value] || null);
const activeDayPlan = computed(() => {
  const day = activePlan.value?.days?.[activeDayIndex.value];
  return day ? { ...activePlan.value, days: [day] } : null;
});
const ragQuery = computed(() => [
  form.value.city,
  ...form.value.preferences,
  form.value.freeText,
].filter(Boolean).join(" "));

onMounted(() => {
  loadHistory();
  loadRagStatus();
  trafficTimer = window.setInterval(refreshTraffic, 5 * 60 * 1000);
});

onBeforeUnmount(() => window.clearInterval(trafficTimer));

function togglePreference(item) {
  const index = form.value.preferences.indexOf(item);
  if (index === -1) form.value.preferences.push(item);
  else form.value.preferences.splice(index, 1);
}

function scrollToTop() {
  window.scrollTo({ top: 0, behavior: "smooth" });
}

async function requestJson(url, options) {
  const response = await fetch(url, options);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(body.message || "服务暂时不可用，请稍后重试");
  return body;
}

async function validateDestination() {
  destinationError.value = "";
  const city = form.value.city.trim();
  if (!city) {
    destinationError.value = "请输入目的地";
    return false;
  }
  try {
    const validation = await requestJson(`${api}/amap/validate?destination=${encodeURIComponent(city)}`);
    if (!validation.valid) {
      destinationError.value = validation.message;
      return false;
    }
    form.value.city = validation.canonicalName || city;
    return true;
  } catch (requestError) {
    destinationError.value = requestError.message;
    return false;
  }
}

async function submit() {
  loading.value = true;
  error.value = "";
  ragEvidence.value = [];
  try {
    if (!(await validateDestination())) throw new Error(destinationError.value || "目的地无效");
    result.value = await requestJson(`${api}/plans`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(form.value),
    });
    activePlanIndex.value = 0;
    activeDayIndex.value = 0;
    await Promise.all([loadRagEvidence(), refreshTraffic(), loadHistory()]);
    window.setTimeout(() => document.querySelector(".result-shell")?.scrollIntoView({ behavior: "smooth", block: "start" }), 0);
  } catch (requestError) {
    error.value = requestError.message;
  } finally {
    loading.value = false;
  }
}

async function loadRagStatus() {
  try {
    ragStatus.value = await requestJson(`${api}/rag/status`);
  } catch {
    ragStatus.value = { status: "DOWN" };
  }
}

async function loadRagEvidence(query = ragQuery.value, city = form.value.city, date = form.value.startDate) {
  if (!query || !city) return;
  try {
    ragEvidence.value = await requestJson(
      `${api}/rag/search?city=${encodeURIComponent(city)}&query=${encodeURIComponent(query)}&targetDate=${date}&limit=4`,
    );
  } catch {
    ragEvidence.value = [];
  }
}

async function refreshTraffic() {
  if (!result.value) return;
  const requests = new Map();
  for (const plan of result.value.plans || []) {
    for (const day of plan.days || []) {
      for (const stop of day.stops || []) {
        if (!stop.attractionId || !/^\d{2}:\d{2}$/.test(stop.time || "")) continue;
        const key = `${stop.attractionId}-${day.date}-${stop.time}`;
        if (!requests.has(key)) requests.set(key, { stop, day });
      }
    }
  }
  await Promise.all([...requests.values()].map(async ({ stop, day }) => {
    try {
      stop.traffic = await requestJson(
        `${api}/traffic/${stop.attractionId}?date=${encodeURIComponent(day.date)}&time=${encodeURIComponent(stop.time)}`,
      );
    } catch {
      // Traffic is supplemental. The itinerary remains usable without a live sample.
    }
  }));
}

async function loadHistory() {
  try {
    histories.value = await requestJson(`${api}/history`);
  } catch {
    histories.value = [];
  }
}

async function openHistory(id) {
  const history = await requestJson(`${api}/history/${id}`);
  result.value = history.plan;
  activePlanIndex.value = 0;
  activeDayIndex.value = 0;
  historyOpen.value = false;
  const profile = history.plan.profile;
  await loadRagEvidence(`${profile.city} ${(profile.preferences || []).join(" ")}`, profile.city, history.plan.plans?.[0]?.days?.[0]?.date || form.value.startDate);
  window.setTimeout(() => document.querySelector(".result-shell")?.scrollIntoView({ behavior: "smooth", block: "start" }), 0);
}

async function removeHistory(id) {
  await requestJson(`${api}/history/${id}`, { method: "DELETE" });
  await loadHistory();
}

function selectPlan(index) {
  activePlanIndex.value = index;
  activeDayIndex.value = 0;
}

function money(value) {
  return value == null ? "待确认" : `¥${Number(value).toLocaleString("zh-CN")}`;
}

function crowdClass(index) {
  return index >= 80 ? "hot" : index >= 60 ? "busy" : "calm";
}

function factTypeLabel(type) {
  return {
    official_fact: "官方事实",
    derived_rule: "规划规则",
    operational_constraint: "运营约束",
    data_governance_rule: "时效提醒",
  }[type] || "知识片段";
}

function timedNote(note) {
  return note.match(/^(\d{2}:\d{2})[-–—](\d{2}:\d{2})\s+(.+)$/);
}

function timelineItems(day) {
  const stops = (day.stops || []).map((stop) => ({ ...stop, type: "attraction" }));
  const notes = (day.scheduleNotes || []).map(timedNote).filter(Boolean).map((match) => {
    const text = match[3];
    const type = /住宿|酒店|入住|退房|行李|寄存/.test(text) ? "lodging"
      : /步行|地铁|公交|打车|前往/.test(text) ? "transport"
      : /早餐推荐|午餐推荐|晚餐推荐/.test(text) ? "meal"
      : /起床|洗漱/.test(text) ? "routine" : "break";
    return { type, time: match[1], endTime: match[2], name: text, url: type === "meal" ? meituanUrl(text) : type === "lodging" ? ctripUrl(day.accommodation) : "" };
  });
  return [...stops, ...notes].sort((left, right) => left.time.localeCompare(right.time));
}

function untimedNotes(day) {
  return (day.scheduleNotes || []).filter((note) => !timedNote(note));
}

function meituanUrl(text) {
  const detail = text.replace(/^.*?[：:]/, "").replace(/（高德评分[^）]*）/g, "").trim();
  return `https://www.meituan.com/s/${encodeURIComponent(`${result.value?.profile?.city || ""} ${detail}`.trim())}`;
}

function ctripUrl(accommodation) {
  if (!accommodation?.name) return "";
  const query = `${result.value?.profile?.city || ""} ${accommodation.name} ${accommodation.address || ""}`.trim();
  return `https://hotels.ctrip.com/hotels/list?searchWord=${encodeURIComponent(query)}`;
}

function scheduleDescription(type) {
  return {
    lodging: "住宿与行李安排以门店当日确认信息为准。",
    meal: "门店来自高德周边搜索，出发前请确认营业与排队情况。",
    transport: "交通方式与耗时来自路线规划，出发时请结合实时路况复核。",
    routine: "起床时间会根据用户特别期待和后续行程动态调整。",
    break: "预留休息缓冲，可结合当天体力灵活调整。",
  }[type] || "";
}
</script>

<template>
  <header class="topbar">
    <button class="brand" type="button" @click="scrollToTop"><span>旅</span> 智游 Agent</button>
    <div class="topbar-actions">
      <span class="service-state" :class="ragStatus?.status === 'UP' ? 'online' : 'offline'">{{ ragStatus?.status === 'UP' ? '知识服务已连接' : '知识服务离线' }}</span>
      <button class="icon-action" type="button" title="历史行程" @click="historyOpen = true; loadHistory()">历史</button>
    </div>
  </header>

  <aside class="history-drawer" :class="{ open: historyOpen }">
    <div class="drawer-head"><div><p class="section-kicker">SAVED PLANS</p><h2>历史行程</h2></div><button type="button" title="关闭" @click="historyOpen = false">×</button></div>
    <p v-if="!histories.length" class="empty-state">还没有保存的行程。</p>
    <article v-for="history in histories" :key="history.id" class="history-row">
      <button class="history-main" type="button" @click="openHistory(history.id)"><b>{{ history.title }}</b><span>{{ history.startDate }} · {{ history.preferences || '综合游览' }}</span><small>{{ history.createdAt.replace('T', ' ').slice(0, 16) }}</small></button>
      <button class="history-delete" type="button" title="删除行程" @click="removeHistory(history.id)">删除</button>
    </article>
  </aside>
  <div v-if="historyOpen" class="drawer-mask" @click="historyOpen = false"></div>

  <main>
    <section class="workspace-intro">
      <div><p class="section-kicker">TRAVEL WORKSPACE</p><h1>生成一份可执行的旅行计划</h1><p>先检索本地旅行知识库，再结合路线、天气、住宿与交通安排形成行程。</p></div>
      <dl class="dataset-summary"><div><dt>知识库</dt><dd>{{ ragStatus?.documents || '—' }}<small>可信片段</small></dd></div><div><dt>覆盖城市</dt><dd>3<small>南京 · 北京 · 杭州</small></dd></div></dl>
    </section>

    <section class="planner-grid" aria-label="行程输入">
      <form class="plan-form" @submit.prevent="submit">
        <div class="form-heading"><span>01</span><div><h2>告诉我你的出行条件</h2><p>目的地、时间、预算和偏好将共同参与路线规划。</p></div></div>
        <div class="form-grid">
          <label>目的地<input v-model="form.city" :class="{ invalid: destinationError }" placeholder="例如：北京、杭州、南京" @input="destinationError = ''" @blur="validateDestination"><small v-if="destinationError" class="field-error">{{ destinationError }}</small></label>
          <label>旅行天数<input v-model.number="form.days" type="number" min="1" max="10"></label>
          <label>出发日期<input v-model="form.startDate" type="date"></label>
          <label>旅行预算（元）<input v-model.number="form.budget" type="number" min="500" max="200000" step="100"></label>
        </div>
        <fieldset><legend>旅行偏好</legend><div class="preference-chips"><button v-for="item in preferences" :key="item" type="button" :class="{ selected: form.preferences.includes(item) }" @click="togglePreference(item)">{{ item }}</button></div></fieldset>
        <label class="expectation">还有什么特别期待？<textarea v-model="form.freeText" placeholder="例如：第二天要爬华山，带老人同行，想早起看日出"></textarea></label>
        <button class="primary-action" type="submit" :disabled="loading">{{ loading ? '正在检索知识并规划路线…' : '生成专属路线' }}</button>
        <p v-if="error" class="error-message">{{ error }}</p>
      </form>

      <aside class="method-card"><p class="section-kicker">PLANNING METHOD</p><h2>有依据的推荐，而不是随机拼接</h2><ol><li><b>01</b><span>优先检索城市 RAG 数据集，并过滤过期或不可信证据。</span></li><li><b>02</b><span>从同一城市 POI 集合中选择路线候选，减少跨区折返。</span></li><li><b>03</b><span>用高德路线、天气和路况信息校正每日节奏。</span></li></ol></aside>
    </section>

    <section v-if="result" class="result-shell">
      <div class="result-heading"><div><p class="section-kicker">YOUR ITINERARY</p><h2>{{ result.profile.city }} · {{ result.profile.days }} 日行程</h2><p class="ai-state" :class="{ active: result.ai?.enabled }">{{ result.ai?.status }}</p></div><time>生成于 {{ result.generatedAt.replace('T', ' ').slice(0, 16) }}</time></div>

      <section v-if="ragEvidence.length" class="evidence-panel">
        <button class="evidence-toggle" type="button" @click="evidenceExpanded = !evidenceExpanded"><span><b>RAG 规划依据</b><small>已检索 {{ ragEvidence.length }} 条 {{ result.profile.city }} 知识片段</small></span><span>{{ evidenceExpanded ? '收起' : '查看证据' }}</span></button>
        <div v-if="evidenceExpanded" class="evidence-list"><article v-for="evidence in ragEvidence" :key="evidence.chunkId"><div><span class="evidence-type">{{ factTypeLabel(evidence.factType) }}</span><b>{{ evidence.poiId }}</b><small>匹配度 {{ Math.round(evidence.score * 100) }}%</small></div><p>{{ evidence.content }}</p><a v-if="evidence.sourceUrl" :href="evidence.sourceUrl" target="_blank" rel="noopener">查看来源与复核信息</a><small v-if="evidence.validUntil">有效期至 {{ evidence.validUntil }}</small></article></div>
      </section>

      <div class="plan-tabs"><button v-for="(plan, index) in result.plans" :key="plan.title" type="button" :class="{ active: activePlanIndex === index }" @click="selectPlan(index)"><span>{{ plan.title }}</span><b>{{ plan.score }}</b><small>{{ plan.description }}</small><em v-if="plan.budget">{{ money(plan.budget.estimated) }}</em></button></div>

      <section v-if="activePlan?.budget" class="budget-card"><div><small>{{ activePlan.budget.tier }}方案预计</small><b>{{ money(activePlan.budget.estimated) }}</b></div><p>目标 {{ money(activePlan.budget.target) }} · 合理区间 {{ money(activePlan.budget.min) }} - {{ money(activePlan.budget.max) }}</p><small>{{ activePlan.budget.note }}</small></section>

      <RouteMap v-if="activePlan" :days="activePlan.days" :transport="result.profile.transport" v-model:activeDay="activeDayIndex" />
      <p v-if="activeDayPlan?.personalizedAdvice" class="personal-advice">{{ activeDayPlan.personalizedAdvice }}</p>

      <section class="day-list">
        <article v-for="day in activeDayPlan?.days || []" :key="day.day" class="day-card">
          <aside class="day-overview"><p>DAY {{ String(day.day).padStart(2, '0') }}</p><h3>{{ day.theme }}</h3><span>{{ day.date }}</span><span>约 {{ Math.floor(day.totalMinutes / 60) }} 小时 · {{ day.travelKm }} km</span><div v-if="day.weather" class="weather-card"><b>{{ day.weather.condition }}</b><span>{{ day.weather.minTemp }} - {{ day.weather.maxTemp }}°C</span><small>降雨 {{ day.weather.rainProbability }}% · {{ day.weather.provider }}</small></div><a v-if="day.accommodation" class="lodging-card" :href="ctripUrl(day.accommodation)" target="_blank" rel="noopener"><b>{{ day.accommodation.sameAsPrevious ? '续住' : '当日住宿' }}</b><span>{{ day.accommodation.name }}</span><small>{{ day.accommodation.luggagePlan }}</small></a><div v-if="day.costs?.length" class="cost-list"><b>当日费用估算</b><span v-for="cost in day.costs" :key="cost.category + cost.label">{{ cost.label }}<strong :class="{ pending: cost.pending }">{{ money(cost.amount) }}</strong></span></div></aside>
          <div class="day-content"><p v-if="day.dailyAdvice" class="day-advice">{{ day.dailyAdvice }}</p><ul v-if="untimedNotes(day).length" class="schedule-notes"><li v-for="note in untimedNotes(day)" :key="note">{{ note }}</li></ul><div v-for="item in timelineItems(day)" :key="`${item.time}-${item.name}`" class="timeline-item" :class="`${item.type}-item`"><time>{{ item.time }}<small>至 {{ item.endTime }}</small></time><i></i><a v-if="item.type !== 'attraction' && item.url" class="timeline-card external" :href="item.url" target="_blank" rel="noopener"><h4>{{ item.name }}</h4><p>{{ scheduleDescription(item.type) }}</p><small>{{ item.type === 'meal' ? '查看美团团购' : '查看携程酒店' }}</small></a><div v-else-if="item.type !== 'attraction'" class="timeline-card"><h4>{{ item.name }}</h4><p>{{ scheduleDescription(item.type) }}</p></div><div v-else class="attraction-card"><div class="attraction-title"><h4>{{ item.name }}</h4><span :class="crowdClass(item.crowdIndex)">{{ item.crowdLevel }} {{ item.crowdIndex }}</span></div><p>{{ item.summary }}</p><p class="crowd-note">{{ item.crowdAdvice }}<small>{{ item.crowdBasis }}</small></p><p v-if="item.personalTip" class="personal-tip">{{ item.personalTip }}</p><div v-if="item.traffic" class="traffic-card" :class="{ unavailable: !item.traffic.available }"><b>周边交通：{{ item.traffic.liveLevel }}</b><span v-if="item.traffic.roadName">{{ item.traffic.roadName }}</span><p>{{ item.traffic.description }}</p><small v-if="item.traffic.sampledAt">采集于 {{ item.traffic.sampledAt }}</small><p>预计拥挤度：{{ item.traffic.forecastLevel }}</p><small>{{ item.traffic.noiseNote }}</small></div><small class="meta-line">{{ item.category }} · 建议 {{ item.durationMinutes }} 分钟 · {{ item.openingHours }}<template v-if="item.transferToNextMinutes"> · 下一站约 {{ item.transferToNextMinutes }} 分钟</template></small></div></div></div>
        </article>
      </section>

      <section v-if="activeDayPlan?.alternatives?.length" class="alternative-section"><p class="section-kicker">ALTERNATIVES</p><h3>备选景点</h3><p>这些景点用于下一次重新生成时参考，不会直接替换当前路线。</p><div><article v-for="alternative in activeDayPlan.alternatives" :key="alternative.attraction.attractionId"><b>{{ alternative.attraction.name }}</b><span>景点热度 {{ alternative.recentHeatScore || '暂无' }}</span><small>{{ alternative.reason }}</small></article></div></section>
      <p class="data-notice">{{ result.dataNotice }}</p>
    </section>
  </main>

  <footer><span>智游 Agent · 可追溯的个性化旅行规划</span><small>数据用于规划参考，开放、预约与限流请以景区官方公告为准。</small></footer>
</template>
