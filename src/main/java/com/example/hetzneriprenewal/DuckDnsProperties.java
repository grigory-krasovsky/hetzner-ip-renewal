package com.example.hetzneriprenewal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("duckdns")
public record DuckDnsProperties(String token, String domains) {

}
