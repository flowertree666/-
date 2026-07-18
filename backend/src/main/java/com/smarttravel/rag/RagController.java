package com.smarttravel.rag;

import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/rag")
public class RagController {
    private final RagService rag;
    public RagController(RagService rag) { this.rag = rag; }
    @GetMapping("/status") public Object status() { return rag.status(); }
    @PostMapping("/rebuild") public Object rebuild() { return rag.rebuild(); }
    @GetMapping("/search") public Object search(@RequestParam String query, @RequestParam(defaultValue="南京市") String city,
                                                      @RequestParam(required=false) LocalDate targetDate, @RequestParam(defaultValue="6") int limit) {
        return rag.search(query, city, targetDate == null ? LocalDate.now() : targetDate, limit);
    }
}
