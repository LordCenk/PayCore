package com.paycore.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API docs at /v3/api-docs, interactive UI at /swagger-ui.html. */
@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI payCoreOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("PayCore API")
                        .version("v1")
                        .description("""
                                Payment processing API. Authenticate with `Authorization: Bearer <api key>` \
                                (from POST /api/v1/merchants). Amounts are in minor units (paise, cents). \
                                POSTs that move money require an `Idempotency-Key` header. \
                                Test tokens such as `tok_success`, `tok_decline` and `tok_timeout` force \
                                processor outcomes."""))
                .components(new Components()
                        .addSecuritySchemes("apiKey", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").description("Merchant API key"))
                        .addSecuritySchemes("adminKey", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Admin-Key")))
                .addSecurityItem(new SecurityRequirement().addList("apiKey"));
    }
}
