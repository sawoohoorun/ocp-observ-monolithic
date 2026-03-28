package com.example.demo.web;

import com.example.demo.service.OrderService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @GetMapping("/ui/orders/{id}")
  public Map<String, Object> getOrderUi(@PathVariable String id) {
    return orderService.getOrder(id);
  }
}
