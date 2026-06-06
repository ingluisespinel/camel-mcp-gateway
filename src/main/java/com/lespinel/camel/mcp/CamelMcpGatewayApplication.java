package com.lespinel.camel.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class CamelMcpGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(CamelMcpGatewayApplication.class, args);
    }
}
