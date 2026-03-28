package com.example.backend.service;

import com.example.backend.domain.OrderEntity;
import com.example.backend.repo.OrderRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

  private final OrderRepository orderRepository;

  public OrderService(OrderRepository orderRepository) {
    this.orderRepository = orderRepository;
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> getOrder(String id) {
    return orderRepository
        .findById(id)
        .map(
            o ->
                Map.<String, Object>of(
                    "orderId",
                    o.getId(),
                    "customer",
                    o.getCustomer(),
                    "amountCents",
                    o.getAmountCents()));
  }
}
