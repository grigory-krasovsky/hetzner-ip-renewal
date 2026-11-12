package com.example.hetzneriprenewal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("hetzner")
public record HetznerProperties(String token, long serverId) {

}
