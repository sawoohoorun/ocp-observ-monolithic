package com.example.backend.service;

import com.example.backend.domain.InventoryEntity;
import com.example.backend.repo.InventoryRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryService {

  private final InventoryRepository inventoryRepository;

  public InventoryService(InventoryRepository inventoryRepository) {
    this.inventoryRepository = inventoryRepository;
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> checkInventory(String sku) {
    return inventoryRepository
        .findById(sku)
        .map(
            i ->
                Map.<String, Object>of(
                    "sku", i.getSku(), "qty", i.getQty(), "warehouse", i.getWarehouse()));
  }
}
