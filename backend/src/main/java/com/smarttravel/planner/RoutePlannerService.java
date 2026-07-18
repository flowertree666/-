package com.smarttravel.planner;

import com.smarttravel.attraction.*;
import com.smarttravel.ai.DeepSeekService;
import com.smarttravel.amap.AmapService;
import com.smarttravel.amap.DestinationValidationService;
import com.smarttravel.history.HistoryService;
import com.smarttravel.research.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smarttravel.weather.WeatherForecastService;
import com.smarttravel.crowd.CrowdPredictionService;
import com.smarttravel.traffic.TrafficMonitoringService;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class RoutePlannerService {
    private final AttractionRepository repository;
    private final DeepSeekService deepSeek;
    private final AmapService amap;
    private final DestinationValidationService destinationValidation;
    private final HistoryService history;
    private final ResearchInsightRepository researchRepository; private final ObjectMapper mapper;
    private final WeatherForecastService weatherForecast;
    private final CrowdPredictionService crowdPrediction;
    private final TrafficMonitoringService trafficMonitoring;
    public RoutePlannerService(AttractionRepository repository, DeepSeekService deepSeek, AmapService amap,DestinationValidationService destinationValidation,HistoryService history,ResearchInsightRepository researchRepository,ObjectMapper mapper,WeatherForecastService weatherForecast,CrowdPredictionService crowdPrediction,TrafficMonitoringService trafficMonitoring) { this.repository = repository; this.deepSeek = deepSeek; this.amap = amap; this.destinationValidation=destinationValidation;this.history=history;this.researchRepository=researchRepository;this.mapper=mapper;this.weatherForecast=weatherForecast;this.crowdPrediction=crowdPrediction;this.trafficMonitoring=trafficMonitoring; }

    public PlanResponse plan(PlanRequest req) {
        var destination=destinationValidation.validate(req.city());
        if(!destination.valid())throw new IllegalArgumentException(destination.message());
        req = new PlanRequest(destination.canonicalName(), req.days(), req.startDate(), req.preferences(), req.pace(), req.transport(), req.budget(), req.freeText());
        var prefs = req.preferences() == null ? List.<String>of() : req.preferences();
        var placeRequirements=deepSeek.extractPlaceRequirements(req);
        if(!placeRequirements.feasibilityIssue().isBlank())throw new IllegalArgumentException(placeRequirements.feasibilityIssue());
        Map<String,ResearchReport.HotAttraction> recentHot=recentHot(req.city(),req.startDate());
        List<Attraction> all = new ArrayList<>(repository.findByCityIgnoreCase(req.city()));
        // A city-specific dataset is authoritative for routing. Cities without a dataset retain the legacy DB/AMap flow.
        List<Attraction> datasetPoi = all.stream().filter(a -> a.getSourceName() != null && a.getSourceName().endsWith(" RAG 数据集")).toList();
        if (!datasetPoi.isEmpty()) all = new ArrayList<>(datasetPoi);
        if (all.isEmpty()) {
            var fetched = amap.searchAttractions(req.city());
            if (!fetched.isEmpty()) all = new ArrayList<>(repository.saveAll(fetched));
        }
        if (all.isEmpty()) throw new IllegalArgumentException("暂未收录该城市，请先同步城市景点数据");
        // Treat a named POI in the user's free-text as a hard constraint even when the LLM is unavailable.
        // This keeps dataset-backed destinations deterministic instead of relying on a best-effort NLP call.
        placeRequirements = enrichRequirementsFromCatalog(placeRequirements, req.freeText(), all);
        List<String> excludedPlaces = placeRequirements.exclude();
        all.removeIf(a->excludedPlaces.stream().anyMatch(name->samePlace(a.getName(),name)));
        List<Attraction> required=new ArrayList<>();List<String> missing=new ArrayList<>();
        Map<Long,Integer> requiredDays=new HashMap<>();Map<String,Integer> reservedNames=new HashMap<>();Set<Long> fullDayIds=new HashSet<>();
        for(var requested:placeRequirements.mustVisit()){String name=requested.name();
            Attraction found=all.stream().filter(a->requestedPlaceMatch(a,name)).findFirst().orElse(null);
            if(found==null){var remote=normalizedPlaceName(name).length()<=3?amap.findAttractionNationwide(name):amap.findAttraction(req.city(),name);if(remote.isEmpty())remote=amap.findAttractionNationwide(name);if(remote.isPresent()){Attraction candidate=remote.get();if(all.stream().mapToDouble(a->distance(a,candidate)).min().orElse(Double.MAX_VALUE)<=300){found=repository.save(candidate);all.add(found);}else missing.add(name);}else missing.add(name);}
            if(found!=null&&!required.contains(found))required.add(found);
            if(found!=null&&requested.day()>0){if(requested.day()>req.days())throw new IllegalArgumentException("指定的第"+requested.day()+"天超出本次"+req.days()+"天行程");requiredDays.put(found.getId(),requested.day());reservedNames.put(normalizedPlaceName(requested.name()),requested.day());}
            if(found!=null&&requested.fullDay())fullDayIds.add(found.getId());
        }
        if(!missing.isEmpty())throw new IllegalArgumentException("在"+req.city()+"未找到你指定的地点："+String.join("、",missing)+"。请检查名称或目的地城市");
        if(placeRequirements.hiking()){Attraction hiking=all.stream().filter(this::isStrenuous).max(Comparator.comparingInt(Attraction::getHeatScore)).orElse(null);if(hiking==null){var remote=amap.findHikingAttraction(req.city());if(remote.isPresent()){hiking=repository.save(remote.get());all.add(hiking);}}if(hiking==null)throw new IllegalArgumentException("未找到可验证的登山/徒步景点，无法用普通城市景点替代“爬山看日出”。请指定山峰或更换目的地。");if(!required.contains(hiking))required.add(hiking);requiredDays.putIfAbsent(hiking.getId(),1);reservedNames.putIfAbsent(normalizedPlaceName(hiking.getName()),1);}
        Set<Long> requiredIds=required.stream().map(Attraction::getId).collect(java.util.stream.Collectors.toSet());
        PlanResponse.RoutePlan economic=build("经济", "以预算下限为优先，保留必要体验与交通", req, all, prefs, recentHot, required, requiredIds, requiredDays, reservedNames, fullDayIds, placeRequirements.wakeTime(), placeRequirements.sunrise(), 0,Set.of());
        Set<Long> economicIds=planAttractionIds(economic);
        PlanResponse.RoutePlan standard=build("标准", "在预算中位数内平衡景点、住宿和餐饮", req, all, prefs, recentHot, required, requiredIds, requiredDays, reservedNames, fullDayIds, placeRequirements.wakeTime(), placeRequirements.sunrise(), 1,economicIds);
        Set<Long> comfortAvoid=new HashSet<>(economicIds);comfortAvoid.addAll(planAttractionIds(standard));
        PlanResponse.RoutePlan comfort=build("舒适", "在预算上限内提升住宿、餐饮和转场舒适度", req, all, prefs, recentHot, required, requiredIds, requiredDays, reservedNames, fullDayIds, placeRequirements.wakeTime(), placeRequirements.sunrise(), 2,comfortAvoid);
        List<PlanResponse.RoutePlan> drafts = List.of(economic,standard,comfort);
        var generated = deepSeek.personalize(req, drafts);
        Map<Long,Attraction> allById = new HashMap<>();
        for (Attraction attraction : all) allById.put(attraction.getId(), attraction);
        Map<Long,Attraction> selectedAttractions = new HashMap<>();
        generated.plans().stream().flatMap(p -> p.days().stream()).flatMap(d -> d.stops().stream()).forEach(s -> {
            Attraction attraction = allById.get(s.attractionId());
            if (attraction != null) selectedAttractions.put(attraction.getId(), attraction);
        });
        trafficMonitoring.watchAndSample(selectedAttractions.values(), req.startDate().plusDays(req.days()));
        List<PlanResponse.RoutePlan> plansWithTraffic = attachTraffic(generated.plans(), req.startDate());
        var profile = new PlanResponse.Profile(req.city(), req.days(), prefs,
                Optional.ofNullable(req.pace()).orElse("适中"), Optional.ofNullable(req.transport()).orElse("公共交通"), budget(req));
        var response = new PlanResponse(profile, plansWithTraffic, LocalDateTime.now(),
                "热度与客流为带时间戳的辅助预测；出发前请复核景区官方预约、开放和限流公告。",
                new PlanResponse.AiMeta(generated.used(), generated.model(), generated.status()));
        history.save(req,response);
        return response;
    }

    private PlanResponse.RoutePlan build(String title, String desc, PlanRequest req, List<Attraction> all, List<String> prefs, Map<String,ResearchReport.HotAttraction> recentHot,List<Attraction> required,Set<Long> requiredIds,Map<Long,Integer> requiredDays,Map<String,Integer> reservedNames,Set<Long> fullDayIds,String wakeTime,boolean sunrise, int mode,Set<Long> avoidIds) {
        int dailyLimit = switch(mode){case 0->360;case 2->540;default->480;};
        Comparator<Attraction> score = Comparator.comparingDouble(a -> rank(a, prefs, recentHot, mode));
        List<Attraction> pool = all.stream().sorted(score.reversed()).toList();
        List<Attraction> anchorPool=pool.stream().filter(a->!requiredDays.containsKey(a.getId())).sorted(Comparator.comparing(a->avoidIds.contains(a.getId()))).toList();if(anchorPool.isEmpty())anchorPool=pool;
        List<Attraction> unassignedRequired=required.stream().filter(a->!requiredDays.containsKey(a.getId())).toList();
        List<Attraction> anchors = selectDayAnchors(anchorPool, unassignedRequired, req.days(), prefs, recentHot, mode, req.transport());
        required.stream().filter(a->requiredDays.containsKey(a.getId())).forEach(a->anchors.set(requiredDays.get(a.getId())-1,a));
        Set<Long> used = new HashSet<>();
        List<PlanResponse.DayPlan> days = new ArrayList<>();
        AmapService.LodgingPlace activeLodging=null;
        for (int d=0; d<req.days(); d++) {
            Attraction anchor = anchors.get(d % anchors.size()); int dayIndex=d;
            boolean recoveryDay=required.stream().anyMatch(a->fullDayIds.contains(a.getId())&&requiredDays.getOrDefault(a.getId(),0)==dayIndex);
            double radius = isDriving(req.transport()) ? switch(mode){case 0->25;case 2->65;default->45;} : switch(mode){case 0->12;case 2->30;default->20;};
            List<Attraction> candidates = pool.stream().filter(a -> !used.contains(a.getId()))
                    .filter(a->!requiredDays.containsKey(a.getId())||requiredDays.get(a.getId())==dayIndex+1)
                    .filter(a->!reservedForOtherDay(a.getName(),reservedNames,dayIndex+1))
                    .filter(a->!recoveryDay||!isStrenuous(a))
                    .filter(a -> nearestAnchor(a, anchors)==dayIndex).toList();
            if (candidates.size()<2) candidates=pool.stream().filter(a->!used.contains(a.getId())).filter(a->!requiredDays.containsKey(a.getId())||requiredDays.get(a.getId())==dayIndex+1).filter(a->!reservedForOtherDay(a.getName(),reservedNames,dayIndex+1)).filter(a->!recoveryDay||!isStrenuous(a)).filter(a->distance(anchor,a)<=radius).toList();
            if (candidates.isEmpty()) candidates = pool.stream().filter(a -> !used.contains(a.getId())).filter(a->!requiredDays.containsKey(a.getId())||requiredDays.get(a.getId())==dayIndex+1).filter(a->!reservedForOtherDay(a.getName(),reservedNames,dayIndex+1)).filter(a->!recoveryDay||!isStrenuous(a)).toList();
            if (candidates.isEmpty()) candidates = List.of(anchor);
            List<Attraction> fixedForDay=required.stream().filter(a->requiredDays.getOrDefault(a.getId(),0)==dayIndex+1).toList();
            if(fixedForDay.stream().anyMatch(a->fullDayIds.contains(a.getId())))candidates=fixedForDay;
            int dayLimit=recoveryDay?Math.min(dailyLimit,300):dailyLimit;List<Attraction> selected = new ArrayList<>(); int minutes = 0;
            double distanceWeight=mode==0?3:mode==2?1.2:2,rankWeight=mode==0?.22:mode==2?.14:.10;
            for (Attraction a : candidates.stream().sorted(Comparator.comparingDouble(x -> (requiredIds.contains(x.getId())?-10000:0)+(avoidIds.contains(x.getId())&&!requiredIds.contains(x.getId())?500:0)+distance(anchor,x)*distanceWeight-rank(x,prefs,recentHot,mode)*rankWeight)).toList()) {
                if(distance(anchor,a)>radius&&!selected.isEmpty())continue;
                int duration=visitDuration(a,fullDayIds);int add = duration + (selected.isEmpty() ? 0 : 35);
                if (requiredIds.contains(a.getId()) || minutes + add <= dayLimit || selected.isEmpty()) { selected.add(a); minutes += add; used.add(a.getId()); }
            }
            double km = 0;
            List<AmapService.TravelLeg> transfers = new ArrayList<>();
            for (int i=1;i<selected.size();i++) {
                Attraction from=selected.get(i-1), to=selected.get(i); var leg=amap.planLeg(from.getLongitude(),from.getLatitude(),to.getLongitude(),to.getLatitude(),req.city(),req.transport());km+=leg.kilometers();transfers.add(leg);
            }
            LocalDate planDate=req.startDate().plusDays(d);double avgLat=selected.stream().mapToDouble(Attraction::getLatitude).average().orElse(anchor.getLatitude()),avgLng=selected.stream().mapToDouble(Attraction::getLongitude).average().orElse(anchor.getLongitude());
            PlanResponse.WeatherInfo weather=weatherForecast.forecast(avgLat,avgLng,planDate);
            AmapService.LodgingPlace previousLodging=activeLodging;boolean lodgingChanged=false;double lodgingRadius=isDriving(req.transport())?18:8;
            if(activeLodging==null||coordinateDistance(activeLodging.longitude(),activeLodging.latitude(),avgLng,avgLat)>lodgingRadius){var candidate=amap.findLodging(avgLng,avgLat,req.city(),mode);if(candidate.isPresent()&&(activeLodging==null||coordinateDistance(activeLodging.longitude(),activeLodging.latitude(),candidate.get().longitude(),candidate.get().latitude())>3)){activeLodging=candidate.get();lodgingChanged=previousLodging!=null;}}
            boolean sameLodging=d>0&&!lodgingChanged;String luggagePlan=activeLodging==null?"未获取到可验证住宿":d==0?"上午先到酒店寄存行李，游览结束且13:00后办理入住":lodgingChanged?"13:00前从上一酒店退房，仅在酒店转场时携带行李；到新酒店后立即寄存，13:00后入住":"继续住同一酒店，行李留在客房，无需随身游览";
            PlanResponse.Accommodation accommodation=activeLodging==null?null:new PlanResponse.Accommodation(activeLodging.name(),activeLodging.address(),activeLodging.rating(),activeLodging.longitude(),activeLodging.latitude(),sameLodging,"13:00后入住 · 次日13:00前退房",luggagePlan);
            LocalTime cursor=parseClock(wakeTime,LocalTime.of(7,0)),dayStart=cursor;List<String> scheduleNotes=new ArrayList<>();LocalTime ready=cursor.plusMinutes(30);scheduleNotes.add(clock(cursor)+"—"+clock(ready)+" 起床与洗漱");cursor=ready;
            boolean checkInPending=activeLodging!=null&&(d==0||lodgingChanged);
            AmapService.LodgingPlace morningLodging=lodgingChanged&&previousLodging!=null?previousLodging:activeLodging;
            Attraction first=selected.get(0);boolean sunriseHike=sunrise&&d==0&&isStrenuous(first);
            if(activeLodging!=null&&d==0&&!sunriseHike){LocalTime stored=cursor.plusMinutes(20);scheduleNotes.add(clock(cursor)+"—"+clock(stored)+" 到达"+activeLodging.name()+"，前台寄存行李（13:00后办理入住）");cursor=stored;}
            if(sunriseHike){scheduleNotes.add(clock(cursor)+"—"+clock(cursor.plusMinutes(15))+" 清晨出发登山，预留山顶看日出时间");if(morningLodging!=null){var sunriseLeg=amap.planLeg(morningLodging.longitude(),morningLodging.latitude(),first.getLongitude(),first.getLatitude(),req.city(),req.transport());km+=sunriseLeg.kilometers();LocalTime arrival=cursor.plusMinutes(sunriseLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(arrival)+" "+sunriseLeg.mode()+"前往"+first.getName()+"看日出，约"+sunriseLeg.minutes()+"分钟（"+sunriseLeg.source()+"）");cursor=arrival;}else{cursor=cursor.plusMinutes(25);scheduleNotes.add(clock(ready)+"—"+clock(cursor)+" 前往"+first.getName()+"看日出");}}
            else{double breakfastLng=morningLodging!=null?morningLodging.longitude():first.getLongitude(),breakfastLat=morningLodging!=null?morningLodging.latitude():first.getLatitude();var breakfast=amap.findDining(breakfastLng,breakfastLat,"早餐",mode);if(morningLodging!=null&&breakfast.isPresent()){var mealLeg=amap.planLeg(morningLodging.longitude(),morningLodging.latitude(),breakfast.get().longitude(),breakfast.get().latitude(),req.city(),req.transport());km+=mealLeg.kilometers();LocalTime reached=cursor.plusMinutes(mealLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(reached)+" "+mealLeg.mode()+"从住宿前往早餐门店，约"+mealLeg.minutes()+"分钟（"+mealLeg.source()+"）");cursor=reached;}LocalTime breakfastEnd=cursor.plusMinutes(40);scheduleNotes.add(clock(cursor)+"—"+clock(breakfastEnd)+" 早餐推荐："+diningText(breakfast,"住宿附近独立早餐店"));cursor=breakfastEnd;if(activeLodging!=null&&lodgingChanged&&previousLodging!=null){if(breakfast.isPresent()){var backLeg=amap.planLeg(breakfast.get().longitude(),breakfast.get().latitude(),previousLodging.longitude(),previousLodging.latitude(),req.city(),req.transport());km+=backLeg.kilometers();LocalTime returned=cursor.plusMinutes(backLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(returned)+" "+backLeg.mode()+"返回原住宿办理退房，约"+backLeg.minutes()+"分钟（"+backLeg.source()+"）");cursor=returned;}LocalTime checkedOut=cursor.plusMinutes(15);scheduleNotes.add(clock(cursor)+"—"+clock(checkedOut)+" 在"+previousLodging.name()+"办理退房（酒店要求13:00前）");cursor=checkedOut;var hotelLeg=amap.planLeg(previousLodging.longitude(),previousLodging.latitude(),activeLodging.longitude(),activeLodging.latitude(),req.city(),req.transport());km+=hotelLeg.kilometers();LocalTime reached=cursor.plusMinutes(hotelLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(reached)+" "+hotelLeg.mode()+"携带行李前往新住宿"+activeLodging.name()+"，约"+hotelLeg.minutes()+"分钟（"+hotelLeg.source()+"）");cursor=reached;LocalTime stored=cursor.plusMinutes(15);scheduleNotes.add(clock(cursor)+"—"+clock(stored)+" 在新住宿前台寄存行李，13:00后办理入住");cursor=stored;var firstLeg=amap.planLeg(activeLodging.longitude(),activeLodging.latitude(),first.getLongitude(),first.getLatitude(),req.city(),req.transport());km+=firstLeg.kilometers();LocalTime arrival=cursor.plusMinutes(firstLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(arrival)+" "+firstLeg.mode()+"从新住宿前往"+first.getName()+"，约"+firstLeg.minutes()+"分钟（"+firstLeg.source()+"）");cursor=arrival;}else if(breakfast.isPresent()){var leg=amap.planLeg(breakfast.get().longitude(),breakfast.get().latitude(),first.getLongitude(),first.getLatitude(),req.city(),req.transport());km+=leg.kilometers();LocalTime arrival=cursor.plusMinutes(leg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(arrival)+" "+leg.mode()+"前往"+first.getName()+"，约"+leg.minutes()+"分钟（"+leg.source()+"）");cursor=arrival;}else if(activeLodging!=null){var leg=amap.planLeg(activeLodging.longitude(),activeLodging.latitude(),first.getLongitude(),first.getLatitude(),req.city(),req.transport());km+=leg.kilometers();LocalTime arrival=cursor.plusMinutes(leg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(arrival)+" "+leg.mode()+"从住宿前往"+first.getName()+"，约"+leg.minutes()+"分钟（"+leg.source()+"）");cursor=arrival;}else{LocalTime arrival=cursor.plusMinutes(25);scheduleNotes.add(clock(cursor)+"—"+clock(arrival)+" 前往"+first.getName()+"，具体方式按住宿地实时规划");cursor=arrival;}}
            if(recoveryDay)scheduleNotes.add("体力恢复日：前一天为全天高强度行程，今天已降低强度并排除登山、长距离徒步项目");if(weather!=null)scheduleNotes.add("天气："+weather.condition()+"，"+weather.minTemp()+"—"+weather.maxTemp()+"℃，降雨概率"+weather.rainProbability()+"%。"+weather.advice());List<PlanResponse.Stop> stops = new ArrayList<>();boolean lunchScheduled=false;
            for (int i=0;i<selected.size();i++) { Attraction a=selected.get(i);
                LocalTime opens=openingTime(a.getOpeningHours());if(opens!=null&&cursor.isBefore(opens)){scheduleNotes.add(clock(cursor)+"—"+clock(opens)+" 景点周边休息并等待开放");cursor=opens;}
                int duration=visitDuration(a,fullDayIds);
                boolean crossesLunch=cursor.isBefore(LocalTime.of(12,30))&&cursor.plusMinutes(duration).isAfter(LocalTime.of(12,30));
                if(!lunchScheduled&&!fullDayIds.contains(a.getId())&&((!cursor.isBefore(LocalTime.of(11,30))&&cursor.isBefore(LocalTime.of(14,0)))||crossesLunch)){
                    LocalTime lunchReady=cursor.isBefore(LocalTime.of(11,30))?LocalTime.of(11,30):cursor;
                    if(cursor.isBefore(lunchReady))scheduleNotes.add(clock(cursor)+"—"+clock(lunchReady)+" 景点周边短暂休息");
                    var lunch=amap.findDining(a.getLongitude(),a.getLatitude(),"午餐",mode);
                    if(lunch.isPresent()){
                        var outLeg=amap.planLeg(a.getLongitude(),a.getLatitude(),lunch.get().longitude(),lunch.get().latitude(),req.city(),req.transport());km+=outLeg.kilometers();LocalTime arrived=lunchReady.plusMinutes(outLeg.minutes());scheduleNotes.add(clock(lunchReady)+"—"+clock(arrived)+" "+outLeg.mode()+"前往午餐门店，约"+outLeg.minutes()+"分钟（"+outLeg.source()+"）");
                        LocalTime lunchEnd=arrived.plusHours(1);scheduleNotes.add(clock(arrived)+"—"+clock(lunchEnd)+" 午餐推荐："+diningText(lunch,"景点附近餐厅"));
                        var backLeg=amap.planLeg(lunch.get().longitude(),lunch.get().latitude(),a.getLongitude(),a.getLatitude(),req.city(),req.transport());km+=backLeg.kilometers();LocalTime returned=lunchEnd.plusMinutes(backLeg.minutes());scheduleNotes.add(clock(lunchEnd)+"—"+clock(returned)+" "+backLeg.mode()+"返回"+a.getName()+"，约"+backLeg.minutes()+"分钟（"+backLeg.source()+"）");cursor=returned;
                    }else{LocalTime lunchEnd=lunchReady.plusHours(1);scheduleNotes.add(clock(lunchReady)+"—"+clock(lunchEnd)+" 午餐：景点附近餐厅（请结合实时营业状态选择）");cursor=lunchEnd;}
                    lunchScheduled=true;
                }
                int embeddedBreak=0;if(fullDayIds.contains(a.getId())&&cursor.isBefore(LocalTime.NOON)&&cursor.plusMinutes(duration).isAfter(LocalTime.NOON)){scheduleNotes.add("12:00—13:00 午餐推荐："+diningText(amap.findDining(a.getLongitude(),a.getLatitude(),"午餐",mode),"景区内餐饮")+"，并进行体力恢复");embeddedBreak=60;lunchScheduled=true;}LocalTime end=cursor.plusMinutes(duration+embeddedBreak);AmapService.TravelLeg transfer=i<transfers.size()?transfers.get(i):null;
                var prediction=crowdPrediction.predict(a,all,planDate,cursor,weather);
                stops.add(new PlanResponse.Stop(a.getId(), a.getName(), a.getCategory(), clock(cursor),clock(end),transfer==null?0:transfer.minutes(),
                        duration, a.getHeatScore(), prediction.index(), prediction.level(), a.getOpeningHours(),prediction.advice(),prediction.bestVisitTime(),prediction.basis(),
                        a.getSummary(), "", a.getLongitude(), a.getLatitude(), freshness(a.getCollectedAt()), null));
                cursor = end;
                if(sunriseHike&&i==0){var sunriseBreakfast=amap.findDining(a.getLongitude(),a.getLatitude(),"早餐",mode);if(sunriseBreakfast.isPresent()){var mealLeg=amap.planLeg(a.getLongitude(),a.getLatitude(),sunriseBreakfast.get().longitude(),sunriseBreakfast.get().latitude(),req.city(),req.transport());km+=mealLeg.kilometers();LocalTime reached=cursor.plusMinutes(mealLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(reached)+" "+mealLeg.mode()+"下山后前往早餐门店，约"+mealLeg.minutes()+"分钟（"+mealLeg.source()+"）");LocalTime breakfastEnd=reached.plusMinutes(40);scheduleNotes.add(clock(reached)+"—"+clock(breakfastEnd)+" 早餐推荐："+diningText(sunriseBreakfast,"登山点附近独立早餐店"));var backLeg=amap.planLeg(sunriseBreakfast.get().longitude(),sunriseBreakfast.get().latitude(),a.getLongitude(),a.getLatitude(),req.city(),req.transport());km+=backLeg.kilometers();LocalTime returned=breakfastEnd.plusMinutes(backLeg.minutes());scheduleNotes.add(clock(breakfastEnd)+"—"+clock(returned)+" "+backLeg.mode()+"返回"+a.getName()+"继续游览，约"+backLeg.minutes()+"分钟（"+backLeg.source()+"）");cursor=returned;}}
                if(transfer!=null){LocalTime arrival=cursor.plusMinutes(transfer.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(arrival)+" "+transfer.mode()+"前往"+selected.get(i+1).getName()+"，约"+transfer.minutes()+"分钟（"+transfer.source()+"）");cursor=arrival;}
            }
            Attraction last=selected.get(selected.size()-1);
            if(!lunchScheduled){LocalTime lunchStart=cursor.isBefore(LocalTime.of(11,30))?LocalTime.of(11,30):cursor;if(cursor.isBefore(lunchStart))scheduleNotes.add(clock(cursor)+"—"+clock(lunchStart)+" 自由活动");LocalTime lunchEnd=lunchStart.plusHours(1);scheduleNotes.add(clock(lunchStart)+"—"+clock(lunchEnd)+" 午餐推荐："+diningText(amap.findDining(last.getLongitude(),last.getLatitude(),"午餐",mode),"最后景点附近餐厅"));cursor=lunchEnd;}
            double eveningLng=last.getLongitude(),eveningLat=last.getLatitude();
            if(checkInPending&&activeLodging!=null){var hotelLeg=amap.planLeg(last.getLongitude(),last.getLatitude(),activeLodging.longitude(),activeLodging.latitude(),req.city(),req.transport());km+=hotelLeg.kilometers();LocalTime reached=cursor.plusMinutes(hotelLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(reached)+" "+hotelLeg.mode()+"前往"+activeLodging.name()+"办理入住，约"+hotelLeg.minutes()+"分钟（"+hotelLeg.source()+"）");cursor=reached;if(cursor.isBefore(LocalTime.of(13,0))){scheduleNotes.add(clock(cursor)+"—13:00 在酒店公共区域休息，等待可入住时间");cursor=LocalTime.of(13,0);}LocalTime checkedIn=cursor.plusMinutes(20);scheduleNotes.add(clock(cursor)+"—"+clock(checkedIn)+" 办理入住并将寄存行李送入客房");cursor=checkedIn;eveningLng=activeLodging.longitude();eveningLat=activeLodging.latitude();}
            var dinner=amap.findDining(eveningLng,eveningLat,"晚餐",mode);
            LocalTime dinnerStart;
            if(dinner.isPresent()){
                var dinnerLeg=amap.planLeg(eveningLng,eveningLat,dinner.get().longitude(),dinner.get().latitude(),req.city(),req.transport());km+=dinnerLeg.kilometers();LocalTime earliestDeparture=LocalTime.of(17,30).minusMinutes(dinnerLeg.minutes());LocalTime departure=cursor.isAfter(earliestDeparture)?cursor:earliestDeparture;if(cursor.isBefore(departure))scheduleNotes.add(clock(cursor)+"—"+clock(departure)+" 自由休息");LocalTime arrival=departure.plusMinutes(dinnerLeg.minutes());scheduleNotes.add(clock(departure)+"—"+clock(arrival)+" "+dinnerLeg.mode()+"前往晚餐门店，约"+dinnerLeg.minutes()+"分钟（"+dinnerLeg.source()+"）");dinnerStart=arrival;
            }else{dinnerStart=cursor.isBefore(LocalTime.of(17,30))?LocalTime.of(17,30):cursor;if(cursor.isBefore(dinnerStart))scheduleNotes.add(clock(cursor)+"—"+clock(dinnerStart)+" 自由休息并前往晚餐门店");}
            LocalTime dinnerEnd=dinnerStart.plusHours(1);scheduleNotes.add(clock(dinnerStart)+"—"+clock(dinnerEnd)+" 晚餐推荐："+diningText(dinner,"最后景点附近餐厅"));cursor=dinnerEnd;
            if(activeLodging!=null){double returnLng=dinner.map(AmapService.DiningPlace::longitude).orElse(eveningLng),returnLat=dinner.map(AmapService.DiningPlace::latitude).orElse(eveningLat);if(coordinateDistance(returnLng,returnLat,activeLodging.longitude(),activeLodging.latitude())>.1){var returnLeg=amap.planLeg(returnLng,returnLat,activeLodging.longitude(),activeLodging.latitude(),req.city(),req.transport());km+=returnLeg.kilometers();LocalTime returned=cursor.plusMinutes(returnLeg.minutes());scheduleNotes.add(clock(cursor)+"—"+clock(returned)+" "+returnLeg.mode()+"返回住宿"+activeLodging.name()+"，约"+returnLeg.minutes()+"分钟（"+returnLeg.source()+"）");cursor=returned;}}
            String theme = selected.stream().map(Attraction::getDistrict).filter(Objects::nonNull).distinct().limit(2).reduce((a,b)->a+" · "+b).orElse("城市探索");
            int actualMinutes=(int)Duration.between(dayStart,cursor).toMinutes();
            days.add(new PlanResponse.DayPlan(d+1, planDate, theme, "", weather, actualMinutes, Math.round(km*10)/10.0, accommodation, scheduleNotes, stops, budgetCosts(selected,d,req.days(),mode,budget(req),km,req.transport())));
        }
        int planScore = Math.max(70, 96 - mode * 2 - Math.max(0, req.days()*2 - all.size()));
        var alternatives=pool.stream().filter(a->!used.contains(a.getId())).limit(5).map(a->{var hot=findHot(a,recentHot);var stop=new PlanResponse.Stop(a.getId(),a.getName(),a.getCategory(),"待安排","待安排",0,a.getDurationMinutes(),a.getHeatScore(),a.getCrowdIndex(),crowd(a.getCrowdIndex()),a.getOpeningHours(),"选择具体日期和时段后重新预测","待计算","基础热度与空间分布模型",a.getSummary(),"",a.getLongitude(),a.getLatitude(),freshness(a.getCollectedAt()),null);return new PlanResponse.Alternative(stop,hot==null?0:hot.heatScore(),hot==null?"综合评分与偏好匹配后的备选景点":hot.reason());}).toList();
        int target=tierTarget(budget(req),mode),estimated=days.stream().flatMap(d->d.costs().stream()).mapToInt(c->Optional.ofNullable(c.amount()).orElse(0)).sum();
        PlanResponse.BudgetSummary budgetSummary=new PlanResponse.BudgetSummary(tierName(mode),target,(int)Math.round(target*.9),(int)Math.round(target*1.1),estimated,false,"按住宿、餐饮、交通、门票与弹性预留拆分；实时售价需在美团、携程或景区官方页面复核。");
        return new PlanResponse.RoutePlan(title, desc, "", planScore, days, alternatives,budgetSummary);
    }

    private int budget(PlanRequest request){return request.budget()==null?3000:request.budget();}
    private List<PlanResponse.RoutePlan> attachTraffic(List<PlanResponse.RoutePlan> plans, LocalDate startDate) {
        return plans.stream().map(plan -> new PlanResponse.RoutePlan(plan.title(), plan.description(), plan.personalizedAdvice(), plan.score(),
                plan.days().stream().map(day -> new PlanResponse.DayPlan(day.day(), day.date(), day.theme(), day.dailyAdvice(), day.weather(), day.totalMinutes(), day.travelKm(), day.accommodation(), day.scheduleNotes(),
                        day.stops().stream().map(stop -> new PlanResponse.Stop(stop.attractionId(), stop.name(), stop.category(), stop.time(), stop.endTime(), stop.transferToNextMinutes(), stop.durationMinutes(), stop.heatScore(), stop.crowdIndex(), stop.crowdLevel(), stop.openingHours(), stop.crowdAdvice(), stop.bestVisitTime(), stop.crowdBasis(), stop.summary(), stop.personalTip(), stop.longitude(), stop.latitude(), stop.freshness(), trafficMonitoring.information(stop.attractionId(), day.date(), parseClock(stop.time(), LocalTime.NOON)))).toList(), day.costs())).toList(), plan.alternatives(), plan.budget())).toList();
    }
    private Set<Long> planAttractionIds(PlanResponse.RoutePlan plan){return plan.days().stream().flatMap(d->d.stops().stream()).map(PlanResponse.Stop::attractionId).filter(Objects::nonNull).collect(java.util.stream.Collectors.toSet());}
    private int tierTarget(int budget,int mode){return switch(mode){case 0->(int)Math.round(budget*.7);case 2->(int)Math.round(budget*1.3);default->budget;};}
    private String tierName(int mode){return switch(mode){case 0->"经济";case 2->"舒适";default->"标准";};}
    private List<PlanResponse.CostItem> budgetCosts(List<Attraction> stops,int day,int days,int mode,int budget,double km,String transport){
        int target=tierTarget(budget,mode),daily=Math.max(1,target/days),nights=Math.max(1,days-1);List<PlanResponse.CostItem> items=new ArrayList<>();
        int hotel=switch(mode){case 0->220;case 2->520;default->350;};if(day<days-1)items.add(new PlanResponse.CostItem("住宿","当晚住宿",hotel,"预算估算：高德住宿 POI 与档位基准",false));
        int breakfast=switch(mode){case 0->18;case 2->48;default->30;},lunch=switch(mode){case 0->42;case 2->110;default->65;},dinner=switch(mode){case 0->58;case 2->150;default->90;};items.add(new PlanResponse.CostItem("餐饮","早餐",breakfast,"预算估算：高德周边餐饮",false));items.add(new PlanResponse.CostItem("餐饮","午餐",lunch,"预算估算：高德周边餐饮",false));items.add(new PlanResponse.CostItem("餐饮","晚餐",dinner,"预算估算：高德周边餐饮",false));
        for(Attraction stop:stops){int ticket=estimatedTicket(stop,mode);String source=ticket==0?"高德 POI 类别，按免费开放估算":"预算估算：高德 POI 未提供实时票价";items.add(new PlanResponse.CostItem("门票",stop.getName()+" 门票",ticket,source,false));}
        int transportCost=estimatedTransport(km,transport);items.add(new PlanResponse.CostItem("交通","当日高德路线交通",transportCost,"预算估算：高德路线距离与交通方式",false));
        int allocated=items.stream().mapToInt(PlanResponse.CostItem::amount).sum();int reserve=Math.max(0,daily-allocated);items.add(new PlanResponse.CostItem("预留","弹性消费与价格浮动预留",reserve,"预算预留",false));return items;
    }
    private int estimatedTicket(Attraction stop,int mode){String text=stop.getName()+" "+stop.getCategory()+" "+Optional.ofNullable(stop.getTags()).orElse("");if(text.matches(".*(公园|广场|步道).*"))return 0;int base=text.matches(".*(山|峰|峡谷|栈道).*")?120:text.matches(".*(博物|遗址|寺|古).*")?70:50;return switch(mode){case 0->(int)Math.round(base*.8);case 2->(int)Math.round(base*1.2);default->base;};}
    private int estimatedTransport(double km,String transport){if(km<=0)return 0;if(transport!=null&&transport.contains("公共"))return Math.max(2,(int)Math.ceil(km*.45));if(transport!=null&&(transport.contains("自驾")||transport.contains("驾车")))return Math.max(8,(int)Math.ceil(km*.8));return Math.max(12,(int)Math.ceil(12+km*2.4));}

    private double rank(Attraction a, List<String> prefs, Map<String,ResearchReport.HotAttraction> recentHot, int mode) {
        double preference = prefs.stream().anyMatch(p -> (a.getTags()+a.getCategory()).contains(p)) ? 25 : 0;
        double season = a.getBestSeason()!=null && (a.getBestSeason().contains("夏") || a.getBestSeason().contains(String.valueOf(LocalDate.now().getMonthValue()))) ? 12 : 4;
        var hot=findHot(a,recentHot);double recent=hot==null?0:hot.heatScore();
        if(mode==0)return a.getHeatScore()*.22+recent*.18+preference+season+(likelyFreeAttraction(a)?38:0)-a.getCrowdIndex()*.12;
        if(mode==2)return a.getHeatScore()*.58+recent*.52+preference+season-a.getCrowdIndex()*.04;
        return a.getHeatScore()*.36+recent*.34+preference+season-a.getCrowdIndex()*.12;
    }
    private boolean likelyFreeAttraction(Attraction attraction){String text=attraction.getName()+" "+Optional.ofNullable(attraction.getCategory()).orElse("")+" "+Optional.ofNullable(attraction.getTicketInfo()).orElse("");return text.matches(".*(广场|城市公园|开放公园|步道|免费).*");}
    private List<Attraction> selectDayAnchors(List<Attraction> pool,List<Attraction> required,int days,List<String> prefs,Map<String,ResearchReport.HotAttraction> hot,int mode,String transport){
        List<Attraction> anchors=new ArrayList<>();if(pool.isEmpty())return anchors;required.stream().limit(days).forEach(anchors::add);if(anchors.isEmpty())anchors.add(pool.get(0));double maxSpread=isDriving(transport)?90:45;
        while(anchors.size()<days&&anchors.size()<pool.size()){
            Set<String> districts=new HashSet<>();anchors.stream().map(Attraction::getDistrict).filter(Objects::nonNull).forEach(districts::add);
            Attraction next=pool.stream().filter(a->!anchors.contains(a)).max(Comparator.comparingDouble(a->{double nearest=anchors.stream().mapToDouble(x->distance(a,x)).min().orElse(0);double spread=Math.min(nearest,maxSpread);double districtBonus=a.getDistrict()!=null&&!districts.contains(a.getDistrict())?28:0;return spread*2+districtBonus+rank(a,prefs,hot,mode)*0.35;})).orElse(null);
            if(next==null)break;anchors.add(next);
        }
        while(anchors.size()<days)anchors.add(pool.get(anchors.size()%pool.size()));return anchors;
    }
    private int nearestAnchor(Attraction attraction,List<Attraction> anchors){int best=0;double min=Double.MAX_VALUE;for(int i=0;i<anchors.size();i++){double d=distance(attraction,anchors.get(i));if(d<min){min=d;best=i;}}return best;}
    private DeepSeekService.PlaceRequirements enrichRequirementsFromCatalog(DeepSeekService.PlaceRequirements base, String freeText, List<Attraction> catalog) {
        String text = Optional.ofNullable(freeText).orElse("").replaceAll("\\s+", "");
        if (text.isBlank()) return base;

        Map<String, DeepSeekService.RequiredPlace> required = new LinkedHashMap<>();
        for (DeepSeekService.RequiredPlace item : base.mustVisit()) {
            required.put(normalizedPlaceName(item.name()), item);
        }
        for (Attraction attraction : catalog) {
            String name = attraction.getName();
            String normalized = normalizedPlaceName(name);
            String matchedName = catalogMention(text, attraction);
            if (normalized.length() < 4 || matchedName == null) continue;

            int mention = text.indexOf(matchedName);
            String context = text.substring(Math.max(0, mention - 18), Math.min(text.length(), mention + matchedName.length() + 28));
            int day = extractRequestedDay(context);
            boolean fullDay = context.matches(".*(玩一天|一整天|全天|整天).*" );
            DeepSeekService.RequiredPlace existing = required.get(normalized);
            if (existing == null || (existing.day() == 0 && day > 0) || (!existing.fullDay() && fullDay)) {
                required.put(normalized, new DeepSeekService.RequiredPlace(name, day > 0 ? day : (existing == null ? 0 : existing.day()), fullDay || (existing != null && existing.fullDay())));
            }
        }
        return new DeepSeekService.PlaceRequirements(new ArrayList<>(required.values()), base.exclude(), base.hiking(), base.sunrise(), base.feasibilityIssue(), base.wakeTime());
    }
    private int extractRequestedDay(String text) {
        var matcher = java.util.regex.Pattern.compile("(?:第\\s*)?([一二三四五六七八九十0-9]+)\\s*天").matcher(text);
        if (!matcher.find()) return 0;
        String value = matcher.group(1);
        if (value.matches("\\d+")) return Integer.parseInt(value);
        return switch (value) {
            case "一" -> 1; case "二" -> 2; case "三" -> 3; case "四" -> 4; case "五" -> 5;
            case "六" -> 6; case "七" -> 7; case "八" -> 8; case "九" -> 9; case "十" -> 10;
            default -> 0;
        };
    }
    private String catalogMention(String text, Attraction attraction) {
        String name = normalizedPlaceName(attraction.getName());
        if (name.length() >= 4 && text.contains(name)) return name;
        return Arrays.stream(Optional.ofNullable(attraction.getTags()).orElse("").split(","))
                .map(this::normalizedPlaceName)
                .filter(alias -> alias.length() >= 4 && text.contains(alias))
                .findFirst()
                .orElse(null);
    }
    private boolean isDriving(String transport){return transport!=null&&(transport.contains("自驾")||transport.contains("驾车"));}
    private boolean samePlace(String actual,String expected){String a=actual.replaceAll("\\s+","").replaceAll("(旅游景区|风景名胜区|景区)$","");String e=expected.replaceAll("\\s+","").replaceAll("(旅游景区|风景名胜区|景区)$","");return a.contains(e)||e.contains(a);}
    private boolean requestedPlaceMatch(String actual,String expected){if(actual.matches(".*(路|街|站|小区|广场|大厦|酒店|饭店|宾馆|餐厅|商店|公司)$"))return false;String a=normalizedPlaceName(actual),e=normalizedPlaceName(expected);return e.length()<=3?a.equals(e):(a.contains(e)||e.contains(a));}
    private boolean requestedPlaceMatch(Attraction attraction,String expected){
        if(requestedPlaceMatch(attraction.getName(),expected))return true;
        return Arrays.stream(Optional.ofNullable(attraction.getTags()).orElse("").split(","))
                .anyMatch(alias->requestedPlaceMatch(alias,expected));
    }
    private String normalizedPlaceName(String value){return value.replaceAll("\\s+","").replaceAll("(旅游景区|风景名胜区|景区)$","");}
    private int visitDuration(Attraction attraction,Set<Long> fullDayIds){return fullDayIds.contains(attraction.getId())?Math.max(420,attraction.getDurationMinutes()):attraction.getDurationMinutes();}
    private boolean isStrenuous(Attraction attraction){String name=attraction.getName(),text=name+" "+Optional.ofNullable(attraction.getTags()).orElse("")+" "+Optional.ofNullable(attraction.getCategory()).orElse("");if(text.matches(".*(登山|爬山|徒步|栈道|峡谷|攀岩).*"))return true;if(name.matches(".*(塔|楼|寺|祠|馆|广场|公园).*"))return false;return name.contains("山")||name.contains("岭")||name.contains("峰");}
    private boolean reservedForOtherDay(String attractionName,Map<String,Integer> reservedNames,int day){String normalized=normalizedPlaceName(attractionName);return reservedNames.entrySet().stream().anyMatch(e->e.getValue()!=day&&normalized.contains(e.getKey()));}
    private LocalTime dynamicStart(String pace,Attraction first){LocalTime preferred="紧凑".equals(pace)?LocalTime.of(8,0):"轻松".equals(pace)?LocalTime.of(9,30):LocalTime.of(8,45);LocalTime opens=openingTime(first.getOpeningHours());return opens!=null&&opens.isAfter(preferred)?opens:preferred;}
    private LocalTime parseClock(String value,LocalTime fallback){try{return LocalTime.parse(value,DateTimeFormatter.ofPattern("HH:mm"));}catch(Exception e){return fallback;}}
    private String clock(LocalTime value){return value.format(DateTimeFormatter.ofPattern("HH:mm"));}
    private String diningText(Optional<AmapService.DiningPlace> place,String fallback){return place.map(p->p.name()+(p.rating()>0?"（高德评分"+p.rating()+"）":"")+(p.address().isBlank()?"":"，"+p.address())).orElse(fallback+"（请结合实时营业状态选择）");}
    private LocalTime openingTime(String hours){if(hours==null)return null;java.util.regex.Matcher m=java.util.regex.Pattern.compile("([0-2]?\\d)[:：](\\d{2})").matcher(hours);if(!m.find())return null;try{int h=Integer.parseInt(m.group(1)),min=Integer.parseInt(m.group(2));return h<24&&min<60?LocalTime.of(h,min):null;}catch(Exception e){return null;}}
    private Map<String,ResearchReport.HotAttraction> recentHot(String city,LocalDate targetDate){try{return researchRepository.findFirstByCityIgnoreCaseOrderByCreatedAtDesc(city).map(x->{try{ResearchReport r=mapper.readValue(x.getReportJson(),ResearchReport.class);if(r.targetDate()==null||!r.targetDate().equals(targetDate))return Map.<String,ResearchReport.HotAttraction>of();Map<String,ResearchReport.HotAttraction> m=new HashMap<>();Optional.ofNullable(r.hotAttractions()).orElse(List.of()).forEach(h->m.put(h.name(),h));return m;}catch(Exception e){return Map.<String,ResearchReport.HotAttraction>of();}}).orElse(Map.of());}catch(Exception e){return Map.of();}}
    private ResearchReport.HotAttraction findHot(Attraction a,Map<String,ResearchReport.HotAttraction> hot){return hot.entrySet().stream().filter(e->a.getName().contains(e.getKey())||e.getKey().contains(a.getName())).map(Map.Entry::getValue).max(Comparator.comparingInt(ResearchReport.HotAttraction::heatScore)).orElse(null);}
    private String crowd(int v) { return v>=80?"拥挤":v>=60?"较拥挤":v>=35?"舒适":"空闲"; }
    private String freshness(LocalDateTime t) { if(t==null)return "未知"; long h=Duration.between(t, LocalDateTime.now()).toHours(); return h<=24?"今日更新":h<=72?"近3日更新":"待刷新"; }
    private double distance(Attraction a, Attraction b) {
        return coordinateDistance(a.getLongitude(),a.getLatitude(),b.getLongitude(),b.getLatitude());
    }
    private double coordinateDistance(double fromLng,double fromLat,double toLng,double toLat) {
        double lat = Math.toRadians(toLat-fromLat), lon = Math.toRadians(toLng-fromLng);
        double q = Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(fromLat))*Math.cos(Math.toRadians(toLat))*Math.sin(lon/2)*Math.sin(lon/2);
        return 6371*2*Math.atan2(Math.sqrt(q), Math.sqrt(1-q));
    }
}
