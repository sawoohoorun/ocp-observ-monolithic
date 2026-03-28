package com.example.backend.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenTelemetryConfig {

  @Bean
  Tracer tracer(ObjectProvider<OpenTelemetry> openTelemetry) {
    OpenTelemetry otel = openTelemetry.getIfAvailable();
    if (otel == null) {
      return io.opentelemetry.api.trace.TracerProvider.noop().get("noop");
    }
    return otel.getTracer("backend-app");
  }
}
