package com.example.backend.tracing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

/**
 * Names inbound API HTTP observations as {@code step: …} so Jaeger shows one logical {@code app: …}
 * root from the UI and phase-style children on the API pod (same {@code service.name}).
 */
@Configuration
public class ApiServerObservationConfig {

  @Bean
  public ServerRequestObservationConvention apiServerRequestObservationConvention() {
    return new DefaultServerRequestObservationConvention() {
      @Override
      public String getContextualName(ServerRequestObservationContext context) {
        String path = context.getCarrier().getRequestURI();
        if (path != null) {
          if (path.contains("/api/orders/")) {
            return "step: process order";
          }
          if (path.contains("/api/inventory/")) {
            return "step: check inventory";
          }
          if (path.contains("/api/pricing/")) {
            return "step: calculate price";
          }
        }
        return super.getContextualName(context);
      }
    };
  }
}
