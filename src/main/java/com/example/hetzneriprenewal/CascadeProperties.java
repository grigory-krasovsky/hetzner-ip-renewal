package com.example.hetzneriprenewal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("cascade")
public record CascadeProperties(
        String host,
        int sshPort,
        String user,
        String privateKey,
        String knownHosts,
        int probePort
) {

}
