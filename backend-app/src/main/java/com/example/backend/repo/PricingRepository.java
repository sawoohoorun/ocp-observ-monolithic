package com.example.backend.repo;

import com.example.backend.domain.PricingEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PricingRepository extends JpaRepository<PricingEntity, String> {}
