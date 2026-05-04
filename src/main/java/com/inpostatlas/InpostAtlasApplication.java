package com.inpostatlas;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class InpostAtlasApplication {

    public static void main(String[] args) {
        SpringApplication.run(InpostAtlasApplication.class, args);
    }
}

