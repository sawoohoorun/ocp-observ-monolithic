package com.example.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "inventory")
public class InventoryEntity {

  @Id
  @Column(length = 64)
  private String sku;

  @Column(nullable = false)
  private int qty;

  @Column(nullable = false)
  private String warehouse;

  public String getSku() {
    return sku;
  }

  public int getQty() {
    return qty;
  }

  public String getWarehouse() {
    return warehouse;
  }
}
