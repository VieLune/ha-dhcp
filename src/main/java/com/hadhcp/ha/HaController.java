package com.hadhcp.ha;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/ha")
public class HaController {

    private final HaService haService;

    public HaController(HaService haService) {
        this.haService = haService;
    }

    @PostMapping("/role")
    public ResponseEntity<HaStatus> updateRole(@RequestParam HaRole role) {
        haService.updateRole(role);
        return ResponseEntity.ok(haService.status());
    }

    @GetMapping("/status")
    public HaStatus status() {
        return haService.status();
    }
}
