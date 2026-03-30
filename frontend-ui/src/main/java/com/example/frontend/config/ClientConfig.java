package com.example.frontend.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class ClientConfig {

  private final String backendBaseUrl;

  @Autowired
  public ClientConfig(@Value("${demo.backend-base-url}") String backendBaseUrl) {
    this.backendBaseUrl = backendBaseUrl;
  }

  @Bean
  public RestClient backendRestClient() {
    return RestClient.builder().baseUrl(backendBaseUrl).build();
  }
}
