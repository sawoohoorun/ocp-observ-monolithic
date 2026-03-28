package com.example.backend.web;

import com.example.backend.service.InventoryService;
import com.example.backend.service.OrderService;
import com.example.backend.service.PricingService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ApiController {

  private final OrderService orderService;
  private final InventoryService inventoryService;
  private final PricingService pricingService;

  public ApiController(
      OrderService orderService,
      InventoryService inventoryService,
      PricingService pricingService) {
    this.orderService = orderService;
    this.inventoryService = inventoryService;
    this.pricingService = pricingService;
  }

  @GetMapping("/orders/{id}")
  public ResponseEntity<Map<String, Object>> getOrder(@PathVariable String id) {
    return orderService
        .getOrder(id)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/inventory/{sku}")
  public ResponseEntity<Map<String, Object>> inventory(@PathVariable String sku) {
    return inventoryService
        .checkInventory(sku)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }

  @GetMapping("/pricing/{sku}")
  public ResponseEntity<Map<String, Object>> pricing(@PathVariable String sku) {
    return pricingService
        .getPrice(sku)
        .map(ResponseEntity::ok)
        .orElse(ResponseEntity.notFound().build());
  }
}
