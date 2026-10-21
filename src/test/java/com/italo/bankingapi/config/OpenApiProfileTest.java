package com.italo.bankingapi.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.core.properties.SwaggerUiConfigProperties;
import org.springdoc.core.properties.SwaggerUiOAuthProperties;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springdoc.webmvc.ui.SwaggerConfig;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiProfileTest {

    // Load actual profile files and Springdoc MVC infrastructure, without database or JWT fixtures.
    // No security filters: a 404 must mean documentation is absent, not merely unauthorized.
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    HttpMessageConvertersAutoConfiguration.class,
                    WebMvcAutoConfiguration.class,
                    SpringDocConfiguration.class,
                    SpringDocConfigProperties.class,
                    SwaggerUiConfigProperties.class,
                    SwaggerUiOAuthProperties.class,
                    SpringDocWebMvcConfiguration.class,
                    SwaggerConfig.class));

    @Test
    void productionDoesNotPublishDocumentation() {
        runner.withPropertyValues("spring.profiles.active=prod").run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (String path : new String[]{"/v3/api-docs", "/v3/api-docs.yaml",
                    "/v3/api-docs/swagger-config", "/swagger-ui.html",
                    "/swagger-ui/index.html", "/swagger-ui/swagger-initializer.js"}) {
                mvc.perform(get(path)).andExpect(status().isNotFound());
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"default", "dev", "test"})
    void nonProductionKeepsDocumentationAvailable(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile).run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.openapi").exists());
            mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isOk());
            mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.url").value("/v3/api-docs"));
            mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
            mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
            mvc.perform(get("/swagger-ui/swagger-initializer.js")).andExpect(status().isOk());
        });
    }
}
