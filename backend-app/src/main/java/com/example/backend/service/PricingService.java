package com.example.backend.service;

import com.example.backend.domain.PricingEntity;
import com.example.backend.repo.PricingRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {

  private final Tracer tracer;
  private final PricingRepository pricingRepository;

  public PricingService(Tracer tracer, PricingRepository pricingRepository) {
    this.tracer = tracer;
    this.pricingRepository = pricingRepository;
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> getPrice(String sku) {
    Span business = tracer.spanBuilder("backend: calculate price").startSpan();
    try (Scope scope = business.makeCurrent()) {
      business.setAttribute("pricing.sku", sku);

      Span db =
          tracer
              .spanBuilder("SELECT pricing")
              .setSpanKind(SpanKind.CLIENT)
              .setAttribute("db.system", "postgresql")
              .setAttribute("db.operation", "SELECT")
              .setAttribute("db.sql.table", "pricing")
              .setAttribute("db.statement", "SELECT * FROM pricing WHERE sku = ?")
              .startSpan();
      try (Scope dbScope = db.makeCurrent()) {
        Optional<PricingEntity> row = pricingRepository.findById(sku);
        return row.map(p -> Map.<String, Object>of("sku", p.getSku(), "priceCents", p.getCents()));
      } finally {
        db.end();
      }
    } finally {
      business.end();
    }
  }
}
