package com.example.hetzneriprenewal;

import io.github.sinuscosinustan.hetznercloud.HetznerCloudAPI;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HetznerIpRenewalApplication {

    public static void main(String[] args) {
        SpringApplication.run(HetznerIpRenewalApplication.class, args);
    }

    @Bean
    HetznerCloudAPI hetznerCloudApi(HetznerProperties properties) {
        return new HetznerCloudAPI(properties.token());
    }

}
