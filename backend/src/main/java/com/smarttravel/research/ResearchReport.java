package com.smarttravel.research;

import java.time.*;
import java.util.List;

public record ResearchReport(String city,LocalDate targetDate,int heatScore,String heatTrend,String confidence,String summary,
        List<String> seasonalHighlights,List<String> positiveFeedback,List<String> cautions,
        List<HotAttraction> hotAttractions,List<Evidence> evidence,LocalDateTime generatedAt,String notice) {
    public record HotAttraction(String name,int heatScore,String reason,List<Integer> evidenceIds){}
    public record Evidence(int id,String title,String url,String domain,String sourceType,String publishedAt,
                           String dateSource,LocalDateTime collectedAt,String excerpt){}
}
