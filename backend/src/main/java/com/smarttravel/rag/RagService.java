package com.smarttravel.rag;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.*;

@Service
public class RagService {
    private final RestClient client;
    private final boolean enabled;

    public RagService(@Value("${app.rag.base-url:http://127.0.0.1:8091}") String baseUrl,
                      @Value("${app.rag.enabled:true}") boolean enabled) {
        this.client = RestClient.builder().baseUrl(baseUrl).build();
        this.enabled = enabled;
    }

    public Object status() {
        if (!enabled) return Map.of("status", "DISABLED");
        try { return client.get().uri("/health").retrieve().body(Object.class); }
        catch (Exception e) { return Map.of("status", "DOWN", "message", "RAG service is unavailable"); }
    }

    public Object rebuild() {
        if (!enabled) throw new IllegalArgumentException("RAG service is disabled");
        return client.post().uri("/rebuild").retrieve().body(Object.class);
    }

    public List<Match> search(String query, String city, LocalDate targetDate, int limit) {
        if (!enabled || query == null || query.isBlank()) return List.of();
        try {
            String datasetCity = normalizeDatasetCity(city);
            JsonNode root = client.get().uri(builder -> builder.path("/search-get")
                    .queryParam("query", query).queryParam("city", datasetCity).queryParam("target_date", targetDate)
                    .queryParam("limit", limit).build()).retrieve().body(JsonNode.class);
            List<Match> matches = new ArrayList<>();
            for (JsonNode item : root.path("matches")) matches.add(new Match(item.path("chunk_id").asText(), item.path("content").asText(),
                    item.path("score").asDouble(), item.path("metadata").path("poi_id").asText(), item.path("metadata").path("fact_type").asText(),
                    item.path("metadata").path("source_url").asText(), item.path("metadata").path("valid_until").asText()));
            return matches;
        } catch (Exception e) {
            System.err.println("RAG search failed: " + e.getMessage());
            return List.of();
        }
    }

    public List<Map<String,Object>> contextFor(String query, String city, LocalDate targetDate) {
        return search(query, city, targetDate, 6).stream().map(match -> Map.<String,Object>of(
                "chunkId", match.chunkId(), "content", match.content(), "score", match.score(), "poiId", match.poiId(),
                "factType", match.factType(), "sourceUrl", match.sourceUrl(), "validUntil", match.validUntil())).toList();
    }

    private String normalizeDatasetCity(String city) {
        if (city == null || city.isBlank()) return city;
        return switch (city.strip()) {
            case "南京" -> "南京市";
            case "北京" -> "北京市";
            case "杭州" -> "杭州市";
            default -> city.strip();
        };
    }

    public record Match(String chunkId, String content, double score, String poiId, String factType, String sourceUrl, String validUntil) {}
}
