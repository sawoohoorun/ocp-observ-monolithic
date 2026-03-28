package com.example.frontend.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Controller
public class UiController {

  private final RestClient backendRestClient;

  public UiController(RestClient backendRestClient) {
    this.backendRestClient = backendRestClient;
  }

  @GetMapping("/")
  public String index() {
    return "index";
  }

  @GetMapping("/action/order/{id}")
  public String getOrder(@PathVariable String id, Model model) {
    return callBackend(model, "Get Order", "/api/orders/" + id);
  }

  @GetMapping("/action/inventory/{sku}")
  public String checkInventory(@PathVariable String sku, Model model) {
    return callBackend(model, "Check Inventory", "/api/inventory/" + sku);
  }

  @GetMapping("/action/pricing/{sku}")
  public String calculatePrice(@PathVariable String sku, Model model) {
    return callBackend(model, "Calculate Price", "/api/pricing/" + sku);
  }

  private String callBackend(Model model, String actionLabel, String path) {
    model.addAttribute("action", actionLabel);
    try {
      String body = backendRestClient.get().uri(path).retrieve().body(String.class);
      model.addAttribute("ok", true);
      model.addAttribute("body", body);
    } catch (RestClientException ex) {
      model.addAttribute("ok", false);
      model.addAttribute("body", ex.getMessage());
    }
    return "result";
  }
}
