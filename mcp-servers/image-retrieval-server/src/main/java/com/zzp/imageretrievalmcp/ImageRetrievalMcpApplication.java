package com.zzp.imageretrievalmcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ImageRetrievalMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ImageRetrievalMcpApplication.class, args);
    }
}
