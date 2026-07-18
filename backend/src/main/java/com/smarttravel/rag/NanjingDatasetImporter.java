package com.smarttravel.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttravel.attraction.Attraction;
import com.smarttravel.attraction.AttractionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

@Component
/** Imports every normalized city dataset found below app.rag.dataset-root. */
public class NanjingDatasetImporter implements ApplicationRunner {
    private final AttractionRepository repository;
    private final ObjectMapper mapper;
    private final String datasetRoot;

    public NanjingDatasetImporter(AttractionRepository repository, ObjectMapper mapper,
                                  @Value("${app.rag.dataset-root}") String datasetRoot) {
        this.repository = repository; this.mapper = mapper; this.datasetRoot = datasetRoot;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path root = Path.of(datasetRoot).normalize();
        if (!Files.isDirectory(root)) return;
        List<Attraction> batch = new ArrayList<>();
        try (var directories = Files.list(root)) {
            for (Path directory : directories.filter(Files::isDirectory).toList()) {
                Path file = directory.resolve("pois.jsonl");
                if (!Files.isRegularFile(file)) continue;
                for (String line : Files.readAllLines(file)) addPoi(line, batch);
            }
        }
        if (!batch.isEmpty()) repository.saveAll(batch);
    }

    private void addPoi(String line, List<Attraction> batch) throws Exception {
        if (line.isBlank()) return;
        JsonNode poi = mapper.readTree(line);
        String datasetPoiId = poi.path("poi_id").asText("");
        String city = poi.path("city").asText("");
        if (datasetPoiId.isBlank() || city.isBlank()) return;

        String amapId = poi.path("amap_poi_id").asText("");
        String sourceUrl = amapId.isBlank() ? "dataset://" + datasetPoiId : "https://www.amap.com/place/" + amapId;
        double rating = poi.path("amap_rating").asDouble(0);
        Attraction item = repository.findAllBySourceUrl(sourceUrl).stream().findFirst()
                .or(() -> existingDatasetPoi(city, poi.path("name").asText("")))
                .orElseGet(Attraction::new);

        item.setName(poi.path("name").asText());
        item.setCity(city);
        item.setDistrict(poi.path("district").asText());
        item.setCategory(join(poi.path("category")));
        item.setTags(join(poi.path("suitable_tags")) + ",别名:" + join(poi.path("aliases")) + ",数据集POI," + poi.path("physical_intensity").asText("低") + "体力");
        item.setSummary(city + "旅游 RAG 数据集收录。" + poi.path("verification_note").asText("出发前请复核开放、预约和票价信息。"));
        item.setLongitude(poi.path("longitude").asDouble());
        item.setLatitude(poi.path("latitude").asDouble());
        item.setDurationMinutes(poi.path("recommended_duration_minutes").asInt(120));
        item.setHeatScore(rating > 0 ? (int) Math.min(98, 50 + rating * 10) : 65);
        item.setCrowdIndex(50);
        item.setCrowdTrend("RAG 数据集未提供实时客流");
        item.setOpeningHours(poi.path("opening_hours").asText("以官方公告为准"));
        item.setBestSeason(join(poi.path("best_seasons")));
        item.setTicketInfo(ticketInfo(poi));
        item.setSourceName(city + " RAG 数据集");
        item.setSourceUrl(sourceUrl);
        item.setCollectedAt(LocalDateTime.now());
        item.setConfidence(0.9);
        batch.add(item);
    }

    private Optional<Attraction> existingDatasetPoi(String city, String name) {
        return repository.findByCityIgnoreCase(city).stream()
                .filter(item -> name.equalsIgnoreCase(item.getName()))
                .filter(item -> item.getSourceName() != null && item.getSourceName().endsWith(" RAG 数据集"))
                .findFirst();
    }

    private String join(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(item -> { if (!item.asText().isBlank()) values.add(item.asText()); });
        return String.join("、", values);
    }

    private String ticketInfo(JsonNode poi) {
        String explicit = poi.path("ticket_info").asText();
        if (!explicit.isBlank()) return explicit;
        String reservation = poi.path("reservation_rule").asText();
        if (!poi.hasNonNull("ticket_price_min")) return reservation.isBlank() ? "以官方公告为准" : reservation;
        int minimum = poi.path("ticket_price_min").asInt();
        int maximum = poi.path("ticket_price_max").asInt(minimum);
        String price = minimum == maximum ? "参考票价 " + minimum + " 元/人" : "参考票价 " + minimum + "-" + maximum + " 元/人";
        return reservation.isBlank() ? price : price + "；" + reservation;
    }
}
