package com.smarttravel.planner;

import java.time.*;
import java.util.List;

public record PlanResponse(Profile profile, List<RoutePlan> plans, LocalDateTime generatedAt, String dataNotice, AiMeta ai) {
    public record Profile(String city, int days, List<String> preferences, String pace, String transport, int budget) {}
    public record AiMeta(boolean enabled, String model, String status) {}
    public record RoutePlan(String title, String description, String personalizedAdvice, int score, List<DayPlan> days,
                            List<Alternative> alternatives, BudgetSummary budget) {}
    public record Alternative(Stop attraction, int recentHeatScore, String reason) {}
    public record DayPlan(int day, LocalDate date, String theme, String dailyAdvice, WeatherInfo weather, int totalMinutes, double travelKm,
                          Accommodation accommodation, List<String> scheduleNotes, List<Stop> stops, List<CostItem> costs) {}
    public record Accommodation(String name,String address,double rating,double longitude,double latitude,
                                boolean sameAsPrevious,String policy,String luggagePlan) {}
    public record BudgetSummary(String tier,int target,int min,int max,int estimated,boolean hasPending,String note) {}
    public record CostItem(String category,String label,Integer amount,String source,boolean pending) {}
    public record WeatherInfo(String provider,String condition,double minTemp,double maxTemp,int rainProbability,
                              String wind,String advice,String forecastTime) {}
    public record Stop(Long attractionId, String name, String category, String time, String endTime,
                       int transferToNextMinutes, int durationMinutes,
                       int heatScore, int crowdIndex, String crowdLevel, String openingHours,
                       String crowdAdvice, String bestVisitTime, String crowdBasis,
                       String summary, String personalTip, double longitude, double latitude, String freshness,
                       TrafficInfo traffic) {}
    public record TrafficInfo(String liveLevel, String roadName, String description, String sampledAt,
                              Integer averageSpeed, String forecastLevel, String forecastReason,
                              String noiseNote, boolean available) {}
}
