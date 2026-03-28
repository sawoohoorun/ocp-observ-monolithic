package com.example.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "orders")
public class OrderEntity {

  @Id
  @Column(length = 64)
  private String id;

  @Column(nullable = false)
  private String customer;

  @Column(name = "amount_cents", nullable = false)
  private long amountCents;

  public String getId() {
    return id;
  }

  public String getCustomer() {
    return customer;
  }

  public long getAmountCents() {
    return amountCents;
  }
}
