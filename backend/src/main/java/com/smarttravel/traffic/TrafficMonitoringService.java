package com.smarttravel.traffic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttravel.attraction.Attraction;
import com.smarttravel.attraction.AttractionRepository;
import com.smarttravel.planner.PlanResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TrafficMonitoringService {
    private static final Logger log = LoggerFactory.getLogger(TrafficMonitoringService.class);
    private final TrafficWatchRepository watches;
    private final TrafficSnapshotRepository snapshots;
    private final RestClient client = RestClient.create("https://api.map.baidu.com");
    private final String ak;
    private final boolean enabled;
    private final ObjectMapper mapper;
    private final AttractionRepository attractions;
    private final Object trafficRequestLock = new Object();
    private final Map<Long,Object> sampleLocks = new java.util.concurrent.ConcurrentHashMap<>();
    private long lastTrafficRequestAt;

    public TrafficMonitoringService(TrafficWatchRepository watches, TrafficSnapshotRepository snapshots, ObjectMapper mapper, AttractionRepository attractions,
            @Value("${app.baidu.traffic-ak:}") String ak,
            @Value("${app.baidu.traffic-enabled:true}") boolean enabled) {
        this.watches = watches; this.snapshots = snapshots; this.mapper = mapper; this.attractions = attractions; this.ak = ak; this.enabled = enabled;
    }

    public void watchAndSample(Collection<Attraction> attractions, LocalDate expiresOn) {
        if (!available()) return;
        log.info("Registering {} attractions for traffic monitoring", attractions.size());
        for (Attraction attraction : attractions) {
            if (attraction.getId() == null) continue;
            TrafficWatch watch = watches.findByAttractionId(attraction.getId()).orElseGet(TrafficWatch::new);
            watch.setAttractionId(attraction.getId()); watch.setAttractionName(attraction.getName()); watch.setCity(attraction.getCity());
            watch.setLongitude(attraction.getLongitude()); watch.setLatitude(attraction.getLatitude());
            watch.setExpiresOn(expiresOn.plusDays(1)); watch.setUpdatedAt(LocalDateTime.now()); watches.save(watch);
            sample(watch);
        }
    }

    @Scheduled(cron = "0 0/5 * * * *")
    public void refreshWatchedAttractions() {
        if (!available()) return;
        watches.findByExpiresOnGreaterThanEqual(LocalDate.now()).forEach(this::sample);
    }

    public PlanResponse.TrafficInfo information(Long attractionId, LocalDate visitDate, LocalTime visitTime) {
        if (!available()) return unavailable();
        TrafficSnapshot latest = snapshots.findFirstByAttractionIdOrderByCollectedAtDesc(attractionId).orElse(null);
        if (latest == null) {
            synchronized (sampleLocks.computeIfAbsent(attractionId, ignored -> new Object())) {
                if (snapshots.findFirstByAttractionIdOrderByCollectedAtDesc(attractionId).isEmpty())
                    attractions.findById(attractionId).ifPresent(attraction -> watchAndSample(List.of(attraction), LocalDate.now().plusDays(1)));
            }
            latest = snapshots.findFirstByAttractionIdOrderByCollectedAtDesc(attractionId).orElse(null);
        }
        if (latest == null) return new PlanResponse.TrafficInfo("待采样", "", "正在建立景区周边道路交通样本", "", null,
                "数据积累中", "首次生成行程后将每5分钟记录一次周边道路状态", "交通压力不等同于景区园内人数", false);
        List<TrafficSnapshot> history = snapshots.findByAttractionIdAndCollectedAtAfter(attractionId, LocalDateTime.now().minusDays(60));
        double baseline = history.stream().filter(s -> s.getCollectedAt().getDayOfWeek() == visitDate.getDayOfWeek())
                .filter(s -> Math.abs(s.getCollectedAt().getHour() - visitTime.getHour()) <= 1)
                .filter(s -> s.getTrafficStatus() > 0)
                .filter(s -> !s.isPeakHourNoise() && !s.isHoliday() && !s.isPossibleIncident())
                .mapToInt(TrafficSnapshot::getTrafficStatus).average().orElse(latest.getTrafficStatus());
        int predicted = (int) Math.round(baseline);
        String noise = noiseText(latest);
        return new PlanResponse.TrafficInfo(level(latest.getTrafficStatus()), latest.getRoadName(), nonBlank(latest.getDescription(), latest.getStatusDescription()),
                latest.getCollectedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), latest.getAverageSpeed() == null ? null : (int)Math.round(latest.getAverageSpeed()),
                level(predicted), "依据近60天同星期及时段的非通勤、非节假日、非异常路况样本", noise, true);
    }

    private void sample(TrafficWatch watch) {
        try {
            JsonNode around = aroundTraffic(watch, 800);
            String areaLabel = "景区周边800米";
            if (!validTraffic(around)) { around = aroundTraffic(watch, 1000); areaLabel = "景区周边1000米"; }
            if (validTraffic(around)) {
                saveTrafficSnapshot(watch, around, nearbyRoadName(around, areaLabel));
                return;
            }
            String road = nearbyRoad(watch);
            if (road.isBlank()) { saveUnavailableSnapshot(watch, "未找到可查询的景区周边道路，暂不支持实时路况"); return; }
            String roadForQuery = road;
            throttleTrafficRequests();
            String response = client.get().uri(builder -> builder.path("/traffic/v1/road").queryParam("road_name", roadForQuery)
                    .queryParam("city", watch.getCity()).queryParam("coord_type_output", "gcj02").queryParam("ak", ak).build()).retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            if (root == null || root.path("status").asInt(-1) != 0) {
                JsonNode fallbackAround = root != null && root.path("message").asText().contains("road_name") ? aroundTraffic(watch, 1000) : null;
                if (!validTraffic(fallbackAround)) { saveUnavailableSnapshot(watch, "道路实时路况暂不可用：" + (root == null ? "无响应" : root.path("message").asText("接口未返回数据"))); return; }
                root = fallbackAround;
                road = nearbyRoadName(fallbackAround, road);
            }
            int status = root.path("evaluation").path("status").asInt(0);
            JsonNode section = root.path("road_traffic").path(0).path("congestion_sections").path(0);
            LocalDateTime now = LocalDateTime.now();
            TrafficSnapshot item = TrafficSnapshot.builder().attractionId(watch.getAttractionId()).attractionName(watch.getAttractionName())
                    .city(watch.getCity()).roadName(road).trafficStatus(status).statusDescription(root.path("evaluation").path("status_desc").asText())
                    .description(root.path("description").asText()).averageSpeed(number(section, "speed")).congestionDistance(integer(section, "congestion_distance"))
                    .congestionTrend(section.path("congestion_trend").asText()).peakHourNoise(isPeak(now.toLocalTime()))
                    .holiday(isHoliday(now.toLocalDate())).possibleIncident(status >= 3 && number(section, "speed") != null && number(section, "speed") < 12)
                    .collectedAt(now).build();
            snapshots.save(item);
            log.info("Traffic snapshot saved for {} on {}", watch.getAttractionName(), road);
        } catch (Exception error) { log.warn("Traffic sampling failed for {}: {}", watch.getAttractionName(), error.getMessage()); saveUnavailableSnapshot(watch, "路况采样失败，稍后将自动重试"); }
    }

    private void saveUnavailableSnapshot(TrafficWatch watch, String description) {
        if (snapshots.findFirstByAttractionIdOrderByCollectedAtDesc(watch.getAttractionId()).isPresent()) {
            log.warn("Keeping the last successful traffic snapshot for {} after a transient failure", watch.getAttractionName());
            return;
        }
        snapshots.save(TrafficSnapshot.builder().attractionId(watch.getAttractionId()).attractionName(watch.getAttractionName())
                .city(watch.getCity()).roadName("").trafficStatus(0).statusDescription("暂无道路数据").description(description)
                .peakHourNoise(false).holiday(false).possibleIncident(false).collectedAt(LocalDateTime.now()).build());
        log.info("Traffic unavailable for {}: {}", watch.getAttractionName(), description);
    }

    private void saveTrafficSnapshot(TrafficWatch watch, JsonNode root, String road) {
        int status = root.path("evaluation").path("status").asInt(0);
        JsonNode section = firstCongestionSection(root);
        for (JsonNode roadItem : root.path("road_traffic"))
            for (JsonNode congestion : roadItem.path("congestion_sections"))
                status = Math.max(status, congestion.path("status").asInt(0));
        LocalDateTime now = LocalDateTime.now();
        TrafficSnapshot item = TrafficSnapshot.builder().attractionId(watch.getAttractionId()).attractionName(watch.getAttractionName())
                .city(watch.getCity()).roadName(road).trafficStatus(status).statusDescription(root.path("evaluation").path("status_desc").asText())
                .description(root.path("description").asText()).averageSpeed(number(section, "speed")).congestionDistance(integer(section, "congestion_distance"))
                .congestionTrend(section.path("congestion_trend").asText()).peakHourNoise(isPeak(now.toLocalTime()))
                .holiday(isHoliday(now.toLocalDate())).possibleIncident(status >= 3 && number(section, "speed") != null && number(section, "speed") < 12)
                .collectedAt(now).build();
        snapshots.save(item);
        log.info("Traffic snapshot saved for {} around {}", watch.getAttractionName(), road);
    }

    private JsonNode firstCongestionSection(JsonNode root) {
        for (JsonNode road : root.path("road_traffic")) {
            JsonNode section = road.path("congestion_sections").path(0);
            if (!section.isMissingNode()) return section;
        }
        return mapper.createObjectNode();
    }

    private JsonNode aroundTraffic(TrafficWatch watch, int radius) {
        try {
            throttleTrafficRequests();
            String response = client.get().uri(builder -> builder.path("/traffic/v1/around").queryParam("ak", ak)
                    .queryParam("center", watch.getLatitude() + "," + watch.getLongitude()).queryParam("radius", radius)
                    .queryParam("coord_type_input", "gcj02").queryParam("coord_type_output", "gcj02").build()).retrieve().body(String.class);
            return mapper.readTree(response);
        } catch (Exception error) { log.warn("Around traffic lookup failed for {}: {}", watch.getAttractionName(), error.getMessage()); return null; }
    }

    private boolean validTraffic(JsonNode root) {
        if (root == null || root.path("status").asInt(-1) != 0) return false;
        int evaluation = root.path("evaluation").path("status").asInt(0);
        if (evaluation >= 1 && evaluation <= 4) return true;
        if (!root.path("description").asText().isBlank()) return true;
        for (JsonNode item : root.path("road_traffic")) {
            String name = item.path("road_name").asText();
            if (!name.isBlank() && !"UNKNOW".equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    private void throttleTrafficRequests() {
        synchronized (trafficRequestLock) {
            long wait = 250 - (System.currentTimeMillis() - lastTrafficRequestAt);
            if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            lastTrafficRequestAt = System.currentTimeMillis();
        }
    }

    private String nearbyRoadName(JsonNode root, String fallback) {
        for (JsonNode item : root.path("road_traffic")) {
            String name = item.path("road_name").asText();
            if (!name.isBlank() && !"UNKNOW".equalsIgnoreCase(name)) return name;
        }
        return fallback;
    }

    private String nearbyRoad(TrafficWatch watch) {
        try {
            throttleTrafficRequests();
            String response = client.get().uri(builder -> builder.path("/reverse_geocoding/v3/").queryParam("ak", ak).queryParam("output", "json")
                    .queryParam("coordtype", "gcj02ll").queryParam("location", watch.getLatitude() + "," + watch.getLongitude()).queryParam("extensions_road", true).build()).retrieve().body(String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode result = root == null ? null : root.path("result");
            String road = result == null ? "" : result.path("addressComponent").path("street").asText();
            if (road.isBlank() && result != null) road = result.path("roads").path(0).path("name").asText();
            if (road.isBlank() && result != null) road = result.path("business_info").path(0).path("name").asText();
            if (road.isBlank() && result != null) road = result.path("business").asText();
            if (road.isBlank()) log.warn("Reverse geocode returned no road for {}: status={}, business={}", watch.getAttractionName(), root == null ? -1 : root.path("status").asInt(-1), result == null ? "" : result.path("business").asText());
            return road;
        } catch (Exception error) { log.warn("Reverse geocoding failed for {}: {}", watch.getAttractionName(), error.getMessage()); return ""; }
    }

    private boolean available() { return enabled && ak != null && !ak.isBlank(); }
    private boolean isPeak(LocalTime time) { return (time.getHour() >= 7 && time.getHour() < 10) || (time.getHour() >= 17 && time.getHour() < 20); }
    private boolean isHoliday(LocalDate date) { return date.getDayOfWeek().getValue() >= 6; }
    private String level(int status) { return switch (status) { case 1 -> "畅通"; case 2 -> "缓行"; case 3 -> "拥堵"; case 4 -> "严重拥堵"; default -> "未知"; }; }
    private String noiseText(TrafficSnapshot item) { List<String> notes = new ArrayList<>(); if (item.isPeakHourNoise()) notes.add("早晚高峰通勤已标记"); if (item.isHoliday()) notes.add("周末/节假日样本已标记"); if (item.isPossibleIncident()) notes.add("疑似异常拥堵，可能受事故或临时管制影响"); return notes.isEmpty() ? "未发现已识别的通勤或异常噪声" : String.join("；", notes); }
    private String nonBlank(String first, String fallback) { return first == null || first.isBlank() ? fallback : first; }
    private Double number(JsonNode node, String field) { return node == null || node.isMissingNode() || !node.has(field) ? null : node.path(field).asDouble(); }
    private Integer integer(JsonNode node, String field) { return node == null || node.isMissingNode() || !node.has(field) ? null : node.path(field).asInt(); }
    private PlanResponse.TrafficInfo unavailable() { return new PlanResponse.TrafficInfo("未配置", "", "未配置百度实时路况服务", "", null, "不可预测", "配置 BAIDU_TRAFFIC_AK 后启用", "交通压力不等同于景区园内人数", false); }
}
