package com.example.backend.service;

import com.example.backend.domain.InventoryEntity;
import com.example.backend.repo.InventoryRepository;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

  private final Tracer tracer;
  private final InventoryRepository inventoryRepository;

  public InventoryService(Tracer tracer, InventoryRepository inventoryRepository) {
    this.tracer = tracer;
    this.inventoryRepository = inventoryRepository;
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> checkInventory(String sku) {
    Span db =
        tracer
            .spanBuilder("db: select inventory")
            .setSpanKind(SpanKind.CLIENT)
            .setAttribute("db.system", "postgresql")
            .setAttribute("db.operation", "SELECT")
            .setAttribute("db.sql.table", "inventory")
            .setAttribute("db.statement", "SELECT * FROM inventory WHERE sku = ?")
            .startSpan();
    try (Scope dbScope = db.makeCurrent()) {
      db.setAttribute("inventory.sku", sku);
      Optional<InventoryEntity> row = inventoryRepository.findById(sku);
      return row.map(
          i ->
              Map.<String, Object>of(
                  "sku", i.getSku(), "qty", i.getQty(), "warehouse", i.getWarehouse()));
    } finally {
      db.end();
    }
  }
}
