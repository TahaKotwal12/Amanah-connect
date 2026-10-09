package com.amanahconnect.publicapi;

import com.amanahconnect.audit.AuditHandledBy;
import com.amanahconnect.dataexport.DataExportService;
import io.swagger.v3.oas.annotations.Hidden;
import java.net.URI;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The link in the "your data is ready" email. The token is the only credential: unknown, expired and used-up links all look the same (404). */
@RestController
@RequestMapping("/api/v1/public/exports")
@Hidden
public class PublicExportController {

    private final DataExportService service;

    public PublicExportController(DataExportService service) {
        this.service = service;
    }

    @GetMapping("/{token}")
    @AuditHandledBy("DataExportService records DATA_EXPORT_DOWNLOADED")
    public ResponseEntity<Void> open(@PathVariable String token) {
        String location = service.openLink(token);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(location))
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
