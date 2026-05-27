package com.hadhcp.edge;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/edge-devices")
public class EdgeDeviceController {

    private final EdgeDeviceService service;

    public EdgeDeviceController(EdgeDeviceService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<EdgeDeviceResponse>> findAll() {
        return ResponseEntity.ok(service.findAll());
    }

    @GetMapping("/{sn}")
    public ResponseEntity<EdgeDeviceResponse> findBySn(@PathVariable String sn) {
        return service.findBySn(sn)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PutMapping("/{sn}")
    public ResponseEntity<EdgeDeviceResponse> upsert(
            @PathVariable String sn,
            @RequestBody EdgeDeviceUpdateRequest request
    ) {
        return ResponseEntity.ok(service.upsert(sn, request));
    }
}
