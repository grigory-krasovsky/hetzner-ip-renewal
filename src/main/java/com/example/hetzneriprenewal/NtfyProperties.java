package com.example.hetzneriprenewal;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ntfy")
public record NtfyProperties(
        String url,
        String token,
        String commandToken,
        String alertsTopic,
        String commandsTopic
) {

}
