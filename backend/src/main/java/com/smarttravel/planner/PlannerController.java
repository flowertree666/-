package com.smarttravel.planner;

import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class PlannerController {
    private final RoutePlannerService planner;
    public PlannerController(RoutePlannerService planner) { this.planner = planner; }

    @PostMapping("/plans")
    public PlanResponse plan(@Valid @RequestBody PlanRequest request) { return planner.plan(request); }

    @GetMapping("/health")
    public Map<String,Object> health() { return Map.of("status", "UP", "service", "smart-travel"); }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String,String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }
}
