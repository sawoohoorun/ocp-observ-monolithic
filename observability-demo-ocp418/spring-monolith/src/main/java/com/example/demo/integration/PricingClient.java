package com.example.demo.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PricingClient {

  private final RestClient restClient;

  public PricingClient(@Value("${demo.internal-base-url:http://127.0.0.1:8080}") String base) {
    this.restClient = RestClient.builder().baseUrl(base).build();
  }

  public long getPriceCents(String id) {
    String body =
        restClient
            .get()
            .uri("/internal/pricing/{id}/cents", id)
            .retrieve()
            .body(String.class);
    return Long.parseLong(body.trim());
  }
}
