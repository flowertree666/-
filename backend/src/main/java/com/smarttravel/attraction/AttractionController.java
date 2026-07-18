package com.smarttravel.attraction;

import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController @RequestMapping("/api/attractions")
public class AttractionController {
    private final AttractionRepository repository;
    public AttractionController(AttractionRepository repository) { this.repository = repository; }
    @GetMapping public List<Attraction> list(@RequestParam(defaultValue="杭州") String city) {
        return repository.findByCityIgnoreCase(city).stream()
                .sorted(Comparator.comparingInt(Attraction::getHeatScore).reversed()).toList();
    }
}
