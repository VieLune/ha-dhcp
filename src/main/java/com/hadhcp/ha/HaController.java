package com.hadhcp.ha;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ha")
public class HaController {

    private final HaService haService;

    public HaController(HaService haService) {
        this.haService = haService;
    }

    @GetMapping("/status")
    public ResponseEntity<HaStatus> status() {
        return ResponseEntity.ok(haService.status());
    }
}
