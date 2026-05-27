CREATE TABLE edge_device (
    serial_number VARCHAR(128) NOT NULL,
    ip_address VARCHAR(45) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (serial_number)
);

CREATE UNIQUE INDEX uk_edge_device_ip_address ON edge_device (ip_address);

CREATE TABLE ac_config (
    config_key VARCHAR(64) NOT NULL,
    config_json CLOB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (config_key)
);
