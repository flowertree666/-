package com.smarttravel.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.smarttravel.attraction.Attraction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AmapService {
    private final RestClient client=RestClient.create("https://restapi.amap.com");
    private final String key; private final boolean enabled;
    private final Map<String,TravelEstimate> routeCache=new ConcurrentHashMap<>();
    private final Map<String,Boolean> lodgingParentCache=new ConcurrentHashMap<>();
    private final Map<String,Optional<DiningPlace>> diningCache=new ConcurrentHashMap<>();
    private final Map<String,Optional<LodgingPlace>> lodgingCache=new ConcurrentHashMap<>();
    public AmapService(@Value("${app.amap-key:}") String key,@Value("${app.amap-enabled:true}") boolean enabled){this.key=key;this.enabled=enabled;}
    public boolean available(){return enabled&&key!=null&&!key.isBlank();}

    public DestinationValidation validateDestination(String input){
        String value=input==null?"":input.strip();
        if(value.length()<2)return new DestinationValidation(false,"","","目的地至少需要输入2个字符");
        if(!available())return new DestinationValidation(false,"","","目的地校验服务暂不可用，请稍后重试");
        try{JsonNode response=client.get().uri(b->b.path("/v3/geocode/geo").queryParam("key",key).queryParam("address",value).build()).retrieve().body(JsonNode.class);
            JsonNode geo=response.path("geocodes").path(0);if(geo.isMissingNode()||geo.path("formatted_address").asText().isBlank())return new DestinationValidation(false,"","","未找到该目的地，请检查名称或错别字");
            String formatted=geo.path("formatted_address").asText(),level=geo.path("level").asText();
            String normalizedInput=normalizePlace(value),normalizedResult=normalizePlace(formatted);
            boolean exact=normalizedResult.contains(normalizedInput);boolean administrative=Set.of("国家","省","市","区县","乡镇","街道").contains(level);
            if(!exact||!administrative)return new DestinationValidation(false,formatted,level,"请输入真实的城市、区县或行政地区名称");
            String city=scalar(geo.path("city"));String district=scalar(geo.path("district"));String canonical=!city.isBlank()?city:!district.isBlank()?district:formatted;
            return new DestinationValidation(true,canonical,level,"目的地有效");
        }catch(Exception e){return new DestinationValidation(false,"","","目的地校验失败，请稍后重试");}
    }

    public List<Attraction> searchAttractions(String city) {
        if(!available()) return List.of();
        try {
            JsonNode root=client.get().uri(b->b.path("/v5/place/text").queryParam("key",key)
                .queryParam("keywords","景点").queryParam("region",city).queryParam("city_limit",true)
                .queryParam("types","110000").queryParam("show_fields","business").queryParam("page_size",25).build())
                .retrieve().body(JsonNode.class);
            if(root==null||!"1".equals(root.path("status").asText())) return List.of();
            List<Attraction> result=new ArrayList<>();
            for(JsonNode p:root.path("pois")) { Attraction a=toAttraction(city,p); if(a!=null)result.add(a); }
            return result;
        } catch(Exception ignored){return List.of();}
    }

    public Optional<Attraction> findAttraction(String city,String keyword){
        if(!available()||keyword==null||keyword.isBlank())return Optional.empty();
        try{JsonNode root=client.get().uri(b->b.path("/v5/place/text").queryParam("key",key).queryParam("keywords",keyword).queryParam("region",city).queryParam("city_limit",true).queryParam("show_fields","business").queryParam("page_size",10).build()).retrieve().body(JsonNode.class);
            if(root==null||!"1".equals(root.path("status").asText()))return Optional.empty();List<Attraction> candidates=new ArrayList<>();for(JsonNode p:root.path("pois")){Attraction a=toAttraction(city,p);if(a!=null)candidates.add(a);}String expected=normalizePoiName(keyword);return candidates.stream().filter(a->{String actual=normalizePoiName(a.getName());return poiNameMatch(actual,expected)&&!nonScenicName(a.getName());}).min(Comparator.comparingInt(a->Math.abs(a.getName().length()-keyword.length())));
        }catch(Exception e){return Optional.empty();}
    }

    public JsonNode searchPoiRaw(String city,String keyword){
        if(!available()||keyword==null||keyword.isBlank())return null;
        try{
            return client.get().uri(b->b.path("/v5/place/text").queryParam("key",key)
                    .queryParam("keywords",keyword).queryParam("region",city).queryParam("city_limit",true)
                    .queryParam("types","110000").queryParam("show_fields","business,photos")
                    .queryParam("page_size",10).build()).retrieve().body(JsonNode.class);
        }catch(Exception e){return null;}
    }

    public Optional<Attraction> searchExactPoi(String city,String keyword){
        return findAttraction(city,keyword);
    }

    public Optional<Attraction> findAttractionNationwide(String keyword){
        if(!available()||keyword==null||keyword.isBlank())return Optional.empty();
        try{JsonNode root=client.get().uri(b->b.path("/v5/place/text").queryParam("key",key).queryParam("keywords",keyword).queryParam("types","110000").queryParam("show_fields","business").queryParam("page_size",20).build()).retrieve().body(JsonNode.class);if(root==null||!"1".equals(root.path("status").asText()))return Optional.empty();List<Attraction> candidates=new ArrayList<>();for(JsonNode p:root.path("pois")){Attraction a=toAttraction(p.path("cityname").asText(""),p);if(a!=null)candidates.add(a);}String expected=normalizePoiName(keyword);return candidates.stream().filter(a->{String actual=normalizePoiName(a.getName());return poiNameMatch(actual,expected)&&!nonScenicName(a.getName());}).max(Comparator.comparingInt(Attraction::getHeatScore));}catch(Exception e){return Optional.empty();}
    }
    public Optional<Attraction> findHikingAttraction(String city){
        if(!available())return Optional.empty();
        try{JsonNode root=client.get().uri(b->b.path("/v5/place/text").queryParam("key",key).queryParam("keywords","山").queryParam("region",city).queryParam("city_limit",true).queryParam("types","110000").queryParam("show_fields","business").queryParam("page_size",30).build()).retrieve().body(JsonNode.class);if(root==null||!"1".equals(root.path("status").asText()))return Optional.empty();return java.util.stream.StreamSupport.stream(root.path("pois").spliterator(),false).map(p->toAttraction(city,p)).filter(Objects::nonNull).filter(this::isHikingPoi).max(Comparator.comparingInt(Attraction::getHeatScore));}catch(Exception e){return Optional.empty();}
    }
    private boolean isHikingPoi(Attraction attraction){String name=attraction.getName(),text=name+" "+attraction.getTags()+" "+attraction.getCategory();if(text.matches(".*(登山|爬山|徒步|栈道|峡谷|攀岩).*"))return true;if(name.matches(".*(塔|楼|寺|祠|馆|广场|公园).*"))return false;return name.contains("山")||name.contains("岭")||name.contains("峰");}

    private Attraction toAttraction(String city,JsonNode p) {
        String[] point=p.path("location").asText().split(","); if(point.length!=2)return null;
        JsonNode business=p.path("business"); double rating=parse(business.path("rating").asText(),0);
        String type=p.path("type").asText("旅游景点"); String season=seasonFor(type);
        String summary="高德地图收录的"+type+(rating>0?"，综合评分 "+rating:"，暂无公开评分")+"。出发前请复核开放及预约信息。";
        return Attraction.builder().name(p.path("name").asText()).city(city).district(p.path("adname").asText())
            .category(category(type)).tags(type+","+season).summary(summary).longitude(parse(point[0],0)).latitude(parse(point[1],0))
            .durationMinutes(duration(type)).heatScore(rating>0?(int)Math.min(98,50+rating*10):65).crowdIndex(50)
            .crowdTrend("暂无实时客流").openingHours(text(business,"opentime_today","以景区公告为准"))
            .bestSeason(season).ticketInfo("以景区公告为准").sourceName("高德地图开放平台")
            .sourceUrl("https://www.amap.com/place/"+p.path("id").asText()).collectedAt(LocalDateTime.now())
            .confidence(rating>0?0.86:0.72).build();
    }

    public TravelEstimate walking(double fromLng,double fromLat,double toLng,double toLat) {
        String cacheKey=String.format(Locale.ROOT,"%.5f,%.5f-%.5f,%.5f",fromLng,fromLat,toLng,toLat);
        if(!available())return null; if(routeCache.containsKey(cacheKey))return routeCache.get(cacheKey);
        try {
            String uri=UriComponentsBuilder.fromPath("/v5/direction/walking").queryParam("key",key)
                .queryParam("origin",fromLng+","+fromLat).queryParam("destination",toLng+","+toLat)
                .queryParam("show_fields","cost").build().encode().toUriString();
            JsonNode root=client.get().uri(uri).retrieve().body(JsonNode.class); JsonNode path=root.path("route").path("paths").path(0);
            double meters=parse(path.path("distance").asText(),0); int seconds=(int)parse(path.path("cost").path("duration").asText(),0);
            if(meters<=0)return null; TravelEstimate value=new TravelEstimate(meters/1000d,Math.max(1,seconds/60));routeCache.put(cacheKey,value);return value;
        }catch(Exception ignored){return null;}
    }

    public TravelLeg planLeg(double fromLng,double fromLat,double toLng,double toLat,String city,String preferred){
        double directKm=haversine(fromLng,fromLat,toLng,toLat);if(directKm>60){TravelEstimate intercity=driving(fromLng,fromLat,toLng,toLat);if(intercity!=null)return new TravelLeg("城际打车",intercity.kilometers(),intercity.minutes(),"高德城际驾车路线");}
        TravelEstimate walk=walking(fromLng,fromLat,toLng,toLat);if(walk!=null&&walk.kilometers()<=1.8)return new TravelLeg("步行",walk.kilometers(),walk.minutes(),"高德步行路线");
        if(preferred!=null&&preferred.contains("公共")){TravelLeg transit=transit(fromLng,fromLat,toLng,toLat,city);if(transit!=null)return transit;}
        TravelEstimate drive=driving(fromLng,fromLat,toLng,toLat);return drive==null?new TravelLeg("打车",walk==null?0:walk.kilometers(),35,"高德路线暂不可用，使用转场缓冲"):new TravelLeg("打车",drive.kilometers(),drive.minutes(),"高德驾车路线");
    }
    public Optional<DiningPlace> findDining(double lng,double lat,String meal){
        return findDining(lng,lat,meal,1);
    }
    public Optional<DiningPlace> findDining(double lng,double lat,String meal,int tier){if(!available())return Optional.empty();String cacheKey=String.format(Locale.ROOT,"%.3f,%.3f-%s-%d",lng,lat,meal,tier);Optional<DiningPlace> cached=diningCache.get(cacheKey);if(cached!=null)return cached;Set<String> excluded=new HashSet<>();if(tier>0)findDining(lng,lat,meal,0).map(DiningPlace::name).ifPresent(excluded::add);if(tier>1)findDining(lng,lat,meal,1).map(DiningPlace::name).ifPresent(excluded::add);Optional<DiningPlace> result=queryDining(lng,lat,5000,meal,tier,excluded);diningCache.putIfAbsent(cacheKey,result);return diningCache.get(cacheKey);}
    private Optional<DiningPlace> queryDining(double lng,double lat,int radius,String meal,int tier,Set<String> excluded){try{UriComponentsBuilder builder=UriComponentsBuilder.fromPath("/v5/place/around").queryParam("key",key).queryParam("location",lng+","+lat).queryParam("types","050000").queryParam("radius",radius).queryParam("show_fields","business").queryParam("page_size",25);JsonNode root=client.get().uri(builder.build().encode().toUriString()).retrieve().body(JsonNode.class);List<DiningCandidate> places=new ArrayList<>();for(JsonNode p:root.path("pois")){String[] point=p.path("location").asText().split(",");if(point.length!=2||isLodgingDining(p)||!mealSuitable(p,meal)||excluded.contains(p.path("name").asText()))continue;JsonNode business=p.path("business");double rating=parse(business.path("rating").asText(),0),cost=parse(business.path("cost").asText(),0);places.add(new DiningCandidate(new DiningPlace(p.path("name").asText(),p.path("address").asText(),rating,parse(point[0],lng),parse(point[1],lat),cost),p.path("parent").asText()));}return places.stream().filter(p->!parentIsLodging(p.parentId())).max(Comparator.comparingDouble(p->diningTierScore(p.place(),lng,lat,meal,tier))).map(DiningCandidate::place);}catch(Exception e){return Optional.empty();}}
    public Optional<LodgingPlace> findLodging(double lng,double lat,String city){
        return findLodging(lng,lat,city,1);
    }
    public Optional<LodgingPlace> findLodging(double lng,double lat,String city,int tier){if(!available())return Optional.empty();String cacheKey=String.format(Locale.ROOT,"%s-%.2f,%.2f-%d",city,lng,lat,tier);Optional<LodgingPlace> cached=lodgingCache.get(cacheKey);if(cached!=null)return cached;Set<String> excluded=new HashSet<>();if(tier>0)findLodging(lng,lat,city,0).map(LodgingPlace::name).ifPresent(excluded::add);if(tier>1)findLodging(lng,lat,city,1).map(LodgingPlace::name).ifPresent(excluded::add);Optional<LodgingPlace> result=queryLodging(lng,lat,tier,excluded);lodgingCache.putIfAbsent(cacheKey,result);return lodgingCache.get(cacheKey);}
    private Optional<LodgingPlace> queryLodging(double lng,double lat,int tier,Set<String> excluded){try{JsonNode root=client.get().uri(b->b.path("/v5/place/around").queryParam("key",key).queryParam("location",lng+","+lat).queryParam("keywords","酒店").queryParam("types","100000").queryParam("radius",10000).queryParam("show_fields","business").queryParam("page_size",25).build()).retrieve().body(JsonNode.class);List<LodgingPlace> places=new ArrayList<>();for(JsonNode p:root.path("pois")){String[] point=p.path("location").asText().split(",");String type=p.path("type").asText();if(point.length!=2||!type.contains("住宿服务")||excluded.contains(p.path("name").asText()))continue;JsonNode business=p.path("business");double rating=parse(business.path("rating").asText(),0),cost=parse(business.path("cost").asText(),0),plng=parse(point[0],lng),plat=parse(point[1],lat);double distance=haversine(lng,lat,plng,plat);places.add(new LodgingPlace(p.path("name").asText(),p.path("address").asText(),rating,plng,plat,distance,cost));}return places.stream().max(Comparator.comparingDouble(p->lodgingTierScore(p,tier)));}catch(Exception e){return Optional.empty();}}
    private boolean isLodgingDining(JsonNode poi){String name=poi.path("name").asText(),address=poi.path("address").asText(),type=poi.path("type").asText(),lodging="(酒店|宾馆|民宿|客栈|旅舍|旅馆|度假村|度假山庄)";if(type.contains("住宿服务")||name.matches(".*"+lodging+"$"))return true;if(!address.matches(".*"+lodging+".*"))return false;boolean clearlyOutside=address.matches(".*"+lodging+".*(东|西|南|北)(侧|面)?[0-9]+米.*")||address.matches(".*"+lodging+".*(附近|对面|旁边).*");return !clearlyOutside;}
    private boolean mealSuitable(JsonNode poi,String meal){String value=poi.path("name").asText()+" "+poi.path("type").asText();if("早餐".equals(meal))return !value.matches(".*(烧烤|烤肉|火锅|酒吧|酒馆|夜宵|宴会|礼宴|海鲜).*$");if("午餐".equals(meal)||"晚餐".equals(meal))return !value.matches(".*(咖啡|奶茶|茶饮|冷饮|甜品店).*$");return true;}
    private double diningScore(DiningPlace place,double lng,double lat,String meal){double score=place.rating()*2-haversine(lng,lat,place.longitude(),place.latitude())*1.5;String name=place.name();if("早餐".equals(meal)&&name.matches(".*(早餐|面|粉|粥|包|馄饨|烧麦|饺|豆浆|小吃).*"))score+=2.5;if("晚餐".equals(meal)&&name.matches(".*(餐厅|饭店|菜馆|小炒|火锅|烧烤|地锅|羊肉|涮肉).*"))score+=2;return score;}
    private double diningTierScore(DiningPlace place,double lng,double lat,String meal,int tier){double distance=haversine(lng,lat,place.longitude(),place.latitude()),cost=place.averageCost()>0?place.averageCost():55,mealBonus=diningScore(place,lng,lat,meal);return switch(tier){case 0->mealBonus-cost*.10-distance*1.8;case 2->mealBonus+place.rating()*2-cost*.005-distance*1.2;default->mealBonus+place.rating()-cost*.025-distance*1.5;};}
    private double lodgingTierScore(LodgingPlace place,int tier){double cost=place.averageCost()>0?place.averageCost():380;return switch(tier){case 0->place.rating()*1.5-place.distanceKm()*2-cost*.025;case 2->place.rating()*5-place.distanceKm()*1.5+cost*.002;default->place.rating()*3-place.distanceKm()*2-cost*.008;};}
    private boolean parentIsLodging(String parentId){if(parentId==null||parentId.isBlank())return false;return lodgingParentCache.computeIfAbsent(parentId,id->{try{JsonNode root=client.get().uri(b->b.path("/v5/place/detail").queryParam("key",key).queryParam("id",id).build()).retrieve().body(JsonNode.class);JsonNode poi=root.path("pois").path(0);return isLodgingDining(poi);}catch(Exception e){return false;}});}
    private double haversine(double fromLng,double fromLat,double toLng,double toLat){double lat=Math.toRadians(toLat-fromLat),lon=Math.toRadians(toLng-fromLng);double q=Math.sin(lat/2)*Math.sin(lat/2)+Math.cos(Math.toRadians(fromLat))*Math.cos(Math.toRadians(toLat))*Math.sin(lon/2)*Math.sin(lon/2);return 6371*2*Math.atan2(Math.sqrt(q),Math.sqrt(1-q));}
    private TravelEstimate driving(double fromLng,double fromLat,double toLng,double toLat){try{JsonNode root=client.get().uri(b->b.path("/v5/direction/driving").queryParam("key",key).queryParam("origin",fromLng+","+fromLat).queryParam("destination",toLng+","+toLat).queryParam("show_fields","cost").build()).retrieve().body(JsonNode.class);JsonNode path=root.path("route").path("paths").path(0);double meters=parse(path.path("distance").asText(),0);int seconds=(int)parse(path.path("cost").path("duration").asText(),0);return meters<=0?null:new TravelEstimate(meters/1000d,Math.max(1,seconds/60));}catch(Exception e){return null;}}
    private TravelLeg transit(double fromLng,double fromLat,double toLng,double toLat,String city){
        try{
            JsonNode root=client.get().uri(b->b.path("/v3/direction/transit/integrated").queryParam("key",key).queryParam("origin",fromLng+","+fromLat).queryParam("destination",toLng+","+toLat).queryParam("city",city).queryParam("cityd",city).queryParam("strategy",0).build()).retrieve().body(JsonNode.class);
            JsonNode route=root.path("route"),plan=route.path("transits").path(0);int seconds=plan.path("duration").asInt();double km=route.path("distance").asDouble()/1000d;
            if(seconds<=0)return null;
            List<String> lines=new ArrayList<>();boolean metro=false;
            for(JsonNode segment:plan.path("segments"))for(JsonNode line:segment.path("bus").path("buslines")){
                String name=line.path("name").asText().replaceAll("\\([^)]*\\)$","").strip();
                String from=line.path("departure_stop").path("name").asText().strip(),to=line.path("arrival_stop").path("name").asText().strip();
                if(name.isBlank())continue;
                boolean currentMetro=name.contains("地铁")||name.matches(".*[0-9一二三四五六七八九十]+号线.*");metro|=currentMetro;
                String detail=from.isBlank()||to.isBlank()?name:name+"："+from+"→"+to;
                if(!lines.contains(detail))lines.add(detail);
            }
            String mode;
            if(lines.isEmpty())mode=plan.toString().contains("地铁")?"地铁":"公交";
            else mode=(metro?"地铁 ":"公交 ")+String.join("；",lines.stream().limit(3).toList());
            return new TravelLeg(mode,km,Math.max(1,seconds/60),"高德公共交通路线");
        }catch(Exception e){return null;}
    }

    public Weather weather(String city){
        if(!available())return null;
        try{String adcode=resolveAdcode(city); if(adcode==null)return null;
            JsonNode response=client.get().uri(b->b.path("/v3/weather/weatherInfo").queryParam("key",key).queryParam("city",adcode).queryParam("extensions","base").build()).retrieve().body(JsonNode.class);
            JsonNode live=response.path("lives").path(0);
            if(live.isMissingNode())return null;return new Weather(city,live.path("weather").asText(),live.path("temperature").asText(),live.path("winddirection").asText(),live.path("humidity").asText(),live.path("reporttime").asText());
        }catch(Exception ignored){return null;}
    }
    private String resolveAdcode(String city){
        try{JsonNode response=client.get().uri(b->b.path("/v3/geocode/geo").queryParam("key",key).queryParam("address",city).build()).retrieve().body(JsonNode.class);
            JsonNode geo=response.path("geocodes").path(0);String adcode=geo.path("adcode").asText();return adcode.isBlank()?null:adcode;
        }catch(Exception e){return null;}
    }
    private String seasonFor(String type){int m=LocalDate.now().getMonthValue();if(type.contains("滑雪"))return "冬季";if(type.contains("植物")||type.contains("公园"))return m>=3&&m<=5?"春季":"四季";if(type.contains("水")||type.contains("山"))return m>=6&&m<=8?"夏季":"春秋";return "四季";}
    private String category(String t){if(t.contains("博物"))return "博物馆";if(t.contains("公园")||t.contains("山")||t.contains("湖"))return "自然风光";if(t.contains("遗址")||t.contains("寺")||t.contains("古"))return "人文古迹";return "旅游景点";}
    private int duration(String t){return t.contains("博物")?120:t.contains("公园")||t.contains("风景")?180:120;}
    private double parse(String s,double d){try{return Double.parseDouble(s);}catch(Exception e){return d;}}
    private String text(JsonNode n,String field,String fallback){String v=n.path(field).asText();return v.isBlank()?fallback:v;}
    private String scalar(JsonNode n){return n.isTextual()?n.asText():"";}
    private String normalizePlace(String value){return value.replaceAll("\\s+","").replaceAll("(特别行政区|自治州|自治区|地区|城市|省|市|区|县)$","");}
    private String normalizePoiName(String value){return value.replaceAll("\\s+","").replaceAll("[·•()（）\\-—]","").replaceAll("(旅游景区|风景名胜区|景区)$","");}
    private boolean poiNameMatch(String actual,String expected){return expected.length()<=3?(actual.equals(expected)||actual.startsWith(expected)||expected.startsWith(actual)):(actual.contains(expected)||expected.contains(actual));}
    private boolean nonScenicName(String name){
        return name.matches(".*(路|街|站|小区|广场|大厦|酒店|饭店|宾馆|餐厅|商店|公司|检票处|寄存点|牌坊|入口|出口|停车场|售票处|游客中心)$");
    }
    public record TravelEstimate(double kilometers,int minutes){}
    public record TravelLeg(String mode,double kilometers,int minutes,String source){}
    public record DiningPlace(String name,String address,double rating,double longitude,double latitude,double averageCost){}
    private record DiningCandidate(DiningPlace place,String parentId){}
    public record LodgingPlace(String name,String address,double rating,double longitude,double latitude,double distanceKm,double averageCost){}
    public record Weather(String city,String weather,String temperature,String windDirection,String humidity,String reportTime){}
    public record DestinationValidation(boolean valid,String canonicalName,String level,String message){}
}
