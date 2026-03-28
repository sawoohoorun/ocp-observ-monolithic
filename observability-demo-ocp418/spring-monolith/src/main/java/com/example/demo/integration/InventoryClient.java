package com.example.demo.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InventoryClient {

  private final RestClient restClient;

  public InventoryClient(@Value("${demo.internal-base-url:http://127.0.0.1:8080}") String base) {
    this.restClient = RestClient.builder().baseUrl(base).build();
  }

  public boolean checkStock(String id) {
    return Boolean.parseBoolean(
        restClient
            .get()
            .uri("/internal/inventory/{id}/stock", id)
            .retrieve()
            .body(String.class));
  }
}
