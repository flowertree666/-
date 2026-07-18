package com.smarttravel.amap;

import com.smarttravel.attraction.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/amap")
public class AmapController {
    private final AmapService amap; private final AttractionRepository repository; private final DestinationValidationService destinationValidation;
    public AmapController(AmapService amap,AttractionRepository repository,DestinationValidationService destinationValidation){this.amap=amap;this.repository=repository;this.destinationValidation=destinationValidation;}
    @PostMapping("/sync") public Map<String,Object> sync(@RequestParam String city){
        var fetched=amap.searchAttractions(city); if(!fetched.isEmpty())repository.saveAll(fetched);
        return Map.of("city",city,"synced",fetched.size(),"provider","高德地图开放平台","available",amap.available());
    }
    @GetMapping("/weather") public Object weather(@RequestParam String city){return Optional.ofNullable(amap.weather(city)).orElseThrow(()->new IllegalArgumentException("暂未获取到天气信息"));}
    @GetMapping("/validate") public AmapService.DestinationValidation validate(@RequestParam String destination){return destinationValidation.validate(destination);}
    @GetMapping("/poi") public Object poi(@RequestParam String city,@RequestParam String keyword){
        var result=amap.searchPoiRaw(city,keyword);
        if(result==null)throw new IllegalArgumentException("暂未获取到高德 POI 数据");
        return result;
    }
    @GetMapping("/poi-match") public Object poiMatch(@RequestParam String city,@RequestParam String keyword){
        return amap.searchExactPoi(city,keyword).orElseThrow(()->new IllegalArgumentException("未找到名称匹配的高德 POI"));
    }
}
