package com.example.demo.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalApiController {

  @GetMapping("/internal/inventory/{id}/stock")
  public String stock(@PathVariable String id) {
    return String.valueOf(!id.equals("0"));
  }

  @GetMapping("/internal/pricing/{id}/cents")
  public String price(@PathVariable String id) {
    long cents = 999L + Math.max(0, id.length()) * 11L;
    return Long.toString(cents);
  }
}
