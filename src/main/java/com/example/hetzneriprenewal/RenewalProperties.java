package com.example.hetzneriprenewal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("renewal")
public record RenewalProperties(
        Duration checkInterval,
        Duration alertThreshold,
        Duration outageThreshold,
        int probePort,
        Duration probeTimeout,
        Duration shutdownTimeout
) {

}
