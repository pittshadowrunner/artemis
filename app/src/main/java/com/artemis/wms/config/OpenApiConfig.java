package com.artemis.wms.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI artemisOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Artemis WMS API")
                .version("v1")
                .description("API-first WMS. The web UI is just another client; voice is another. No side doors."));
    }
}
