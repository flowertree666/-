package com.smarttravel.crowd;

import com.smarttravel.attraction.Attraction;
import com.smarttravel.planner.PlanResponse;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class CrowdPredictionService {
    public Prediction predict(Attraction target,List<Attraction> city,LocalDate date,LocalTime time,PlanResponse.WeatherInfo weather){int score=score(target,city,date,time,weather);int bestHour=Arrays.stream(new int[]{8,9,10,14,15,16,17}).boxed().min(Comparator.comparingInt(h->score(target,city,date,LocalTime.of(h,0),weather))).orElse(9);String level=score>=80?"拥挤":score>=60?"较拥挤":score>=35?"舒适":"空闲";String advice=score>=80?"当前时段预计拥挤，建议改到"+bestHour+":00左右或选择备选景点":score>=60?"客流偏高，建议提前预约并尽量在"+bestHour+":00左右到达":"预计客流可接受，仍请出发前查看景区官方提示";return new Prediction(score,level,String.format("%02d:00",bestHour),advice,"预测模型：景点热度、周边POI距离衰减、日期时段和天气");}
    private int score(Attraction t,List<Attraction> city,LocalDate date,LocalTime time,PlanResponse.WeatherInfo w){double gravity=0;for(Attraction x:city){if(Objects.equals(x.getId(),t.getId()))continue;double d=distance(t,x);if(d<=20)gravity+=x.getHeatScore()/Math.pow(1+d,1.35);}double spatial=100*(1-Math.exp(-gravity/170));double timeFactor=time.getHour()>=10&&time.getHour()<=11?1.22:time.getHour()>=14&&time.getHour()<=16?1.16:time.getHour()<9?0.72:0.9;double weekend=date.getDayOfWeek().getValue()>=6?1.2:1;double weather=1;if(w!=null){if(w.rainProbability()>=60)weather*=0.68;if(w.maxTemp()>=35&&time.getHour()>=12&&time.getHour()<=17)weather*=0.72;if(w.condition().contains("沙"))weather*=0.55;}double raw=(t.getCrowdIndex()*0.35+t.getHeatScore()*0.3+spatial*0.35)*timeFactor*weekend*weather;return (int)Math.max(5,Math.min(98,Math.round(raw)));}
    private double distance(Attraction a,Attraction b){double lat=Math.toRadians(b.getLatitude()-a.getLatitude()),lon=Math.toRadians(b.getLongitude()-a.getLongitude());double q=Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(a.getLatitude()))*Math.cos(Math.toRadians(b.getLatitude()))*Math.sin(lon/2)*Math.sin(lon/2);return 6371*2*Math.atan2(Math.sqrt(q),Math.sqrt(1-q));}
    public record Prediction(int index,String level,String bestVisitTime,String advice,String basis){}
}
