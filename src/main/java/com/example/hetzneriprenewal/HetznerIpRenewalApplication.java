package com.example.hetzneriprenewal;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class HetznerIpRenewalApplication {

    public static void main(String[] args) {
        SpringApplication.run(HetznerIpRenewalApplication.class, args);
    }

}
