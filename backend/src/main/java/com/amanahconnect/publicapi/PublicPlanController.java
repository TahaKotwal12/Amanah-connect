package com.amanahconnect.publicapi;

import com.amanahconnect.plan.PublicPlanService;
import com.amanahconnect.plan.PublicPlanView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/plans")
@Tag(name = "Public · Plans", description = "The plans and prices shown on the website. No login.")
public class PublicPlanController {

    private final PublicPlanService service;

    public PublicPlanController(PublicPlanService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(
            summary = "List the public plans",
            description = "Active plans marked public, cheapest tier first (by sort order). Prices are strings; a null limit means unlimited. "
                    + "Cached for a minute, so a price change reaches the site within a minute.")
    public ResponseEntity<List<PublicPlanView>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic()).body(service.list());
    }
}
