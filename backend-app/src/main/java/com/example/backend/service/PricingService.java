package com.example.backend.service;

import com.example.backend.domain.PricingEntity;
import com.example.backend.repo.PricingRepository;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PricingService {

  private final PricingRepository pricingRepository;

  public PricingService(PricingRepository pricingRepository) {
    this.pricingRepository = pricingRepository;
  }

  @Transactional(readOnly = true)
  public Optional<Map<String, Object>> getPrice(String sku) {
    return pricingRepository
        .findById(sku)
        .map(p -> Map.<String, Object>of("sku", p.getSku(), "priceCents", p.getCents()));
  }
}
