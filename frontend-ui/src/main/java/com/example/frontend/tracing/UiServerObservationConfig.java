package com.example.frontend.tracing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.http.server.observation.ServerRequestObservationConvention;

@Configuration
public class UiServerObservationConfig {

  @Bean
  public ServerRequestObservationConvention uiServerRequestObservationConvention() {
    return new DefaultServerRequestObservationConvention() {
      @Override
      public String getContextualName(ServerRequestObservationContext context) {
        String path = context.getCarrier().getRequestURI();
        if (path != null) {
          if (path.contains("/action/order/")) {
            return "app: user click get order";
          }
          if (path.contains("/action/inventory/")) {
            return "app: user click check inventory";
          }
          if (path.contains("/action/pricing/")) {
            return "app: user click calculate price";
          }
        }
        return super.getContextualName(context);
      }
    };
  }
}
