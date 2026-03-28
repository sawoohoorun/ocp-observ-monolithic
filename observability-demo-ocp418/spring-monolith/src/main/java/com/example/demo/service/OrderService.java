package com.example.demo.service;

import com.example.demo.integration.InventoryClient;
import com.example.demo.integration.PricingClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

  private final Tracer tracer;
  private final InventoryClient inventoryClient;
  private final PricingClient pricingClient;

  public OrderService(Tracer tracer, InventoryClient inventoryClient, PricingClient pricingClient) {
    this.tracer = tracer;
    this.inventoryClient = inventoryClient;
    this.pricingClient = pricingClient;
  }

  public Map<String, Object> getOrder(String id) {
    Span span = tracer.spanBuilder("OrderService.getOrder").startSpan();
    try (Scope scope = span.makeCurrent()) {
      span.setAttribute("order.id", id);

      boolean inStock = inventoryClient.checkStock(id);
      long priceCents = pricingClient.getPriceCents(id);

      Map<String, Object> body = new HashMap<>();
      body.put("orderId", id);
      body.put("inStock", inStock);
      body.put("priceCents", priceCents);
      body.put("layer", "monolith-ui-to-service-to-integration");
      return body;
    } finally {
      span.end();
    }
  }
}
