package com.example.backend.service;

import com.example.backend.domain.OrderEntity;
import com.example.backend.repo.OrderRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

  private final Tracer tracer;
  private final OrderRepository orderRepository;

  @Autowired
  public OrderService(Tracer tracer, OrderRepository orderRepository) {
    this.tracer = tracer;
    this.orderRepository = orderRepository;
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> getOrder(String id) {
    Span business = tracer.spanBuilder("backend: get order").startSpan();
    try (Scope scope = business.makeCurrent()) {
      business.setAttribute("order.id", id);

      Span db =
          tracer
              .spanBuilder("SELECT orders")
              .setSpanKind(SpanKind.CLIENT)
              .setAttribute("db.system", "postgresql")
              .setAttribute("db.operation", "SELECT")
              .setAttribute("db.sql.table", "orders")
              .setAttribute("db.statement", "SELECT * FROM orders WHERE id = ?")
              .startSpan();
      try (Scope dbScope = db.makeCurrent()) {
        Optional<OrderEntity> row = orderRepository.findById(id);
        return row.map(
            o ->
                Map.<String, Object>of(
                    "orderId",
                    o.getId(),
                    "customer",
                    o.getCustomer(),
                    "amountCents",
                    o.getAmountCents()));
      } finally {
        db.end();
      }
    } finally {
      business.end();
    }
  }
}
