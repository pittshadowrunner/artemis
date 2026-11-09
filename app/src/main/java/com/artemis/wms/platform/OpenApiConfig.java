package com.artemis.wms.platform;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI artemisOpenApi() {
        return new OpenAPI()
            .info(new Info().title("Artemis WMS API").version("v1")
                .description("API-first warehouse management. The web UI and voice are just clients."))
            .components(new Components().addSecuritySchemes("basic",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
            .addSecurityItem(new SecurityRequirement().addList("basic"));
    }
}
