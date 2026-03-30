package com.example.frontend.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfig {

  private final ObjectProvider<OpenTelemetry> openTelemetry;

  @Autowired
  public OpenTelemetryConfig(ObjectProvider<OpenTelemetry> openTelemetry) {
    this.openTelemetry = openTelemetry;
  }

  @Bean
  public Tracer tracer() {
    OpenTelemetry otel = openTelemetry.getIfAvailable();
    if (otel == null) {
      return io.opentelemetry.api.trace.TracerProvider.noop().get("noop");
    }
    return otel.getTracer("frontend-ui");
  }
}
