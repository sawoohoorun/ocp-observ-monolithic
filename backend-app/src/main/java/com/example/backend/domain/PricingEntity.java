package com.example.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pricing")
public class PricingEntity {

  @Id
  @Column(length = 64)
  private String sku;

  @Column(nullable = false)
  private long cents;

  public String getSku() {
    return sku;
  }

  public long getCents() {
    return cents;
  }
}
