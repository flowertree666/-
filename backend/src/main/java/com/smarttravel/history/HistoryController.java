package com.smarttravel.history;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController @RequestMapping("/api/history")
public class HistoryController {
    private final HistoryService service; public HistoryController(HistoryService service){this.service=service;}
    @GetMapping public List<HistoryService.Summary> list(){return service.list();}
    @GetMapping("/{id}") public HistoryService.Detail detail(@PathVariable Long id){return service.detail(id);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) public void delete(@PathVariable Long id){service.delete(id);}
}
