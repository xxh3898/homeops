package dev.homeops.activity.api;

import dev.homeops.activity.ActivityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/activity")
public class ActivityController {
    private final ActivityService service;

    public ActivityController(ActivityService service) {
        this.service = service;
    }

    @GetMapping
    public ActivityPageResponse page(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "25") @Min(1) @Max(100) int limit) {
        return service.page(cursor, limit);
    }
}
