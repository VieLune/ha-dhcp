package com.hadhcp.ha;

public interface LeaseAuthorityHealth {

    boolean isAvailable();

    int memberCount();
}
