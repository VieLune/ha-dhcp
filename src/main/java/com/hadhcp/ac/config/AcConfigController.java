package com.hadhcp.ac.config;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ac/config")
public class AcConfigController {

    private final AcConfigService service;

    public AcConfigController(AcConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<AcRuntimeConfig> current() {
        return ResponseEntity.ok(service.current());
    }

    @PutMapping
    public ResponseEntity<AcRuntimeConfig> update(@RequestBody AcRuntimeConfig request) {
        return ResponseEntity.ok(service.update(request));
    }
}
