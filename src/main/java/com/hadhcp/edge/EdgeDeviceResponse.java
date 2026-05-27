package com.hadhcp.edge;

import java.time.Instant;

public record EdgeDeviceResponse(
        String sn,
        String ip,
        Instant createdAt,
        Instant updatedAt
) {
}
