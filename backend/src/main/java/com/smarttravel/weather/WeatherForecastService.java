package com.smarttravel.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarttravel.planner.PlanResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import java.time.*;
import java.util.*;

@Service
public class WeatherForecastService {
    private final RestClient http=RestClient.create(); private final String openWeatherKey,weatherApiKey;
    public WeatherForecastService(@Value("${app.weather.openweather-key:}")String openWeatherKey,@Value("${app.weather.weatherapi-key:}")String weatherApiKey){this.openWeatherKey=openWeatherKey;this.weatherApiKey=weatherApiKey;}
    public PlanResponse.WeatherInfo forecast(double lat,double lng,LocalDate date){PlanResponse.WeatherInfo value=openWeather(lat,lng,date);return value!=null?value:weatherApi(lat,lng,date);}
    private PlanResponse.WeatherInfo openWeather(double lat,double lng,LocalDate date){if(openWeatherKey.isBlank())return null;try{
        JsonNode root=http.get().uri("https://api.openweathermap.org/data/2.5/forecast?lat={lat}&lon={lng}&appid={key}&units=metric&lang=zh_cn",lat,lng,openWeatherKey).retrieve().body(JsonNode.class);
        List<JsonNode> items=new ArrayList<>();root.path("list").forEach(x->{if(x.path("dt_txt").asText().startsWith(date.toString()))items.add(x);});if(items.isEmpty())return null;
        double min=items.stream().mapToDouble(x->x.path("main").path("temp_min").asDouble()).min().orElse(0),max=items.stream().mapToDouble(x->x.path("main").path("temp_max").asDouble()).max().orElse(0);int rain=items.stream().mapToInt(x->(int)Math.round(x.path("pop").asDouble()*100)).max().orElse(0);JsonNode noon=items.stream().min(Comparator.comparingInt(x->Math.abs(hour(x)-12))).orElse(items.get(0));String condition=noon.path("weather").path(0).path("description").asText();double wind=noon.path("wind").path("speed").asDouble();return new PlanResponse.WeatherInfo("OpenWeather",condition,min,max,rain,wind+" m/s",advice(condition,max,rain),noon.path("dt_txt").asText());
    }catch(Exception e){return null;}}
    private PlanResponse.WeatherInfo weatherApi(double lat,double lng,LocalDate date){if(weatherApiKey.isBlank()||date.isAfter(LocalDate.now().plusDays(2)))return null;try{
        JsonNode root=http.get().uri("https://api.weatherapi.com/v1/forecast.json?key={key}&q={point}&days=3&aqi=no&alerts=yes",weatherApiKey,lat+","+lng).retrieve().body(JsonNode.class);for(JsonNode f:root.path("forecast").path("forecastday")){if(!date.toString().equals(f.path("date").asText()))continue;JsonNode d=f.path("day");String condition=d.path("condition").path("text").asText();double max=d.path("maxtemp_c").asDouble(),min=d.path("mintemp_c").asDouble();int rain=d.path("daily_chance_of_rain").asInt();return new PlanResponse.WeatherInfo("WeatherAPI",condition,min,max,rain,d.path("maxwind_kph").asText()+" km/h",advice(condition,max,rain),f.path("date").asText());}return null;
    }catch(Exception e){return null;}}
    private int hour(JsonNode n){try{return LocalDateTime.parse(n.path("dt_txt").asText().replace(' ','T')).getHour();}catch(Exception e){return 12;}}
    private String advice(String c,double max,int rain){String x=c.toLowerCase();if(x.contains("沙")||x.contains("storm"))return "天气风险较高，优先室内景点并准备防护用品";if(rain>=60||x.contains("雨"))return "降雨概率较高，建议准备雨具并优先室内行程";if(max>=35)return "高温天气，户外景点安排在早晚并加强防晒补水";if(max<=0)return "气温较低，注意保暖和道路结冰风险";return "天气总体适宜，仍请出发前复核临近预报";}
}
