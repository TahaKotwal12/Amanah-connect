package com.amanahconnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class AmanahConnectApplication {

    public static void main(String[] args) {
        SpringApplication.run(AmanahConnectApplication.class, args);
    }
}
