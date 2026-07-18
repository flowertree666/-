package com.smarttravel.traffic;

import com.smarttravel.planner.PlanResponse;
import org.springframework.web.bind.annotation.*;
import java.time.*;

@RestController
@RequestMapping("/api/traffic")
public class TrafficController {
    private final TrafficMonitoringService traffic;
    public TrafficController(TrafficMonitoringService traffic) { this.traffic = traffic; }

    @GetMapping("/{attractionId}")
    public PlanResponse.TrafficInfo information(@PathVariable Long attractionId,
            @RequestParam LocalDate date, @RequestParam String time) {
        return traffic.information(attractionId, date, LocalTime.parse(time));
    }
}
