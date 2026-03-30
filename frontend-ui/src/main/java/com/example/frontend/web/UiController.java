package com.example.frontend.web;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Controller
public class UiController {

  private final RestClient backendRestClient;
  private final Tracer tracer;

  @Autowired
  public UiController(RestClient backendRestClient, Tracer tracer) {
    this.backendRestClient = backendRestClient;
    this.tracer = tracer;
  }

  @GetMapping("/")
  public String index() {
    return "index";
  }

  @GetMapping("/action/order/{id}")
  public String getOrder(@PathVariable String id, Model model) {
    return callBackend(
        model, "Get Order", "frontend: load order page", "/api/orders/" + id);
  }

  @GetMapping("/action/inventory/{sku}")
  public String checkInventory(@PathVariable String sku, Model model) {
    return callBackend(
        model, "Check Inventory", "frontend: load inventory page", "/api/inventory/" + sku);
  }

  @GetMapping("/action/pricing/{sku}")
  public String calculatePrice(@PathVariable String sku, Model model) {
    return callBackend(
        model, "Calculate Price", "frontend: load pricing page", "/api/pricing/" + sku);
  }

  private String callBackend(
      Model model, String actionLabel, String loadPageSpanName, String path) {
    model.addAttribute("action", actionLabel);
    Span load = tracer.spanBuilder(loadPageSpanName).startSpan();
    try (Scope loadScope = load.makeCurrent()) {
      load.setAttribute("demo.action", actionLabel);
      Span callBackend = tracer.spanBuilder("frontend: call backend").startSpan();
      try (Scope callScope = callBackend.makeCurrent()) {
        callBackend.setAttribute("http.route", path);
        try {
          String body =
              backendRestClient.get().uri(path).retrieve().body(String.class);
          model.addAttribute("ok", true);
          model.addAttribute("body", body);
        } catch (RestClientException ex) {
          model.addAttribute("ok", false);
          model.addAttribute("body", ex.getMessage());
        }
      } finally {
        callBackend.end();
      }
    } finally {
      load.end();
    }
    return "result";
  }
}
