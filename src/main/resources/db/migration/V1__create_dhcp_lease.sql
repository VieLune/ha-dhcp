CREATE TABLE dhcp_lease (
    ip_address VARCHAR(45) NOT NULL,
    mac_address VARCHAR(32) NOT NULL,
    device_id VARCHAR(128),
    lease_state VARCHAR(32) NOT NULL,
    lease_start_time TIMESTAMP WITH TIME ZONE,
    lease_expire_time TIMESTAMP WITH TIME ZONE,
    last_seen_time TIMESTAMP WITH TIME ZONE,
    owner_node_id VARCHAR(128),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (ip_address)
);

CREATE UNIQUE INDEX uk_dhcp_lease_mac_address ON dhcp_lease (mac_address);
CREATE INDEX idx_dhcp_lease_state ON dhcp_lease (lease_state);
CREATE INDEX idx_dhcp_lease_expire_time ON dhcp_lease (lease_expire_time);
