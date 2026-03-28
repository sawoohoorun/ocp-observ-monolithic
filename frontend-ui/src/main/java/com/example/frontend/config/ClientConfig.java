package com.example.frontend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientConfig {

  @Bean
  RestClient backendRestClient(@Value("${demo.backend-base-url}") String baseUrl) {
    return RestClient.builder().baseUrl(baseUrl).build();
  }
}
