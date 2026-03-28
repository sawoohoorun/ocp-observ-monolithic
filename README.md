# OpenShift Container Platform 4.18 — Observability Demo (Tempo + OpenTelemetry + Spring Monolith)

This document is a **production-like, demo-friendly** deployment package for **OpenShift Container Platform 4.18**: **Red Hat OpenShift distributed tracing platform (Tempo Operator)**, **Red Hat build of OpenTelemetry Operator**, and a **single Spring Boot monolith** that emits **end-to-end traces** (minimum three spans; this design targets four to five) over **OTLP** through an in-cluster **OpenTelemetry Collector** into **Tempo**, with **no Helm**, **no Argo CD**, **no PVCs**, and **no PersistentVolumes** (ephemeral / in-memory only).

---

## Architecture summary

- **Operator namespaces (cluster pattern)**  
  - **Tempo Operator** runs in **`openshift-tempo-operator`** (Subscription + OperatorGroup).  
  - **Red Hat build of OpenTelemetry Operator** runs in **`openshift-opentelemetry-operator`**.  
  - Both operators are installed in **“All namespaces”** mode (empty `OperatorGroup.spec`), which is the default Red Hat pattern for these operators on OCP 4.x.

- **Workload namespace**  
  - **`observability-demo`** hosts:  
    - **`TempoMonolithic`** (single Tempo pod, **in-memory trace storage** via tmpfs — no PVC).  
    - **`OpenTelemetryCollector`** `Deployment` (receives OTLP from the app; exports traces to Tempo).  
    - **Spring Boot monolith** (`Deployment`, `Service`, `Route`).  

- **Why `TempoMonolithic` instead of `TempoStack` in this package**  
  - **`TempoStack`** is the scalable microservices-style CR. On OpenShift it is designed around **object storage** (S3-compatible, ODF, etc.) and, in typical configurations, **PVC-backed ingesters** (`storageSize` / `storageClassName` in the CRD schema). That conflicts with **“no PVC anywhere.”**  
  - **`TempoMonolithic`** is the **single-pod, monolithic Tempo** mode (aligned with Grafana Tempo “monolithic deployment”), with **`spec.storage.traces.backend: memory`** — traces live in a **bounded memory volume (tmpfs)** and are **lost on pod restart**, which is appropriate for a **lab/demo** and satisfies **no PVC / no PV**.  
  - **Jaeger UI** is enabled with an **OpenShift Route** (`spec.jaegerui.route.enabled: true`), protected by **oauth-proxy** as provided by the operator.

- **Trace path**  
  1. Browser or `curl` → **OpenShift Route** → Spring Boot `GET /ui/orders/{id}`.  
  2. Spring creates a **server span** (HTTP instrumentation) and nested spans (service + outbound HTTP to loopback).  
  3. OTLP **HTTP** → **`otel-collector.observability-demo.svc:4318`**.  
  4. Collector **OTLP gRPC** → **`tempo-demo.observability-demo.svc:4317`**.  
  5. Query via **Jaeger UI Route** (authenticated) or **in-cluster** `tempo-demo:3200` with `oc port-forward`.

---

## OCP 4.18-specific assumptions

- **OCP 4.18** with default **OperatorHub** / **`redhat-operators`** `CatalogSource` in **`openshift-marketplace`** (typical connected install).  
- You can authenticate as **`cluster-admin`** with **`oc`**.  
- **Restricted/default security contexts** apply; manifests use **non-root**, **read-only root filesystem** where practical, **dropped capabilities**, and **no privileged** containers for the sample app.  
- **Web console** navigation uses **Administrator** perspective terminology consistent with OCP 4.18 (**Operators → OperatorHub**, **Networking → Routes**, etc.).  
- **OpenShift 4.20+** may label the software catalog differently (“Ecosystem Software Catalog”); on **4.18**, use **OperatorHub** as below.

---

## File tree

```text
observability-demo-ocp418/
├── 00-namespace.yaml
├── 01-operatorgroup.yaml
├── 02-subscriptions.yaml
├── 10-tempo.yaml                    # TempoMonolithic (not TempoStack — see architecture)
├── 11-otel-collector.yaml
├── 20-app-configmap.yaml            # optional: extra config (can be omitted if using env only)
├── 21-app-deployment.yaml
├── 22-app-service.yaml
├── 23-app-route.yaml
├── 30-smoketest.md
└── spring-monolith/                 # optional local build context
    ├── pom.xml
    ├── Dockerfile
    └── src/main/java/com/example/demo/
        ├── DemoApplication.java
        ├── config/
        │   └── OpenTelemetryConfig.java
        ├── web/
        │   ├── OrderController.java
        │   └── InternalApiController.java
        ├── service/
        │   └── OrderService.java
        └── integration/
            ├── InventoryClient.java
            └── PricingClient.java
    └── src/main/resources/
        └── application.yaml
```

---

## Prerequisites

1. **OpenShift CLI** (`oc`) matching your cluster (4.18).  
2. Log in: `oc login …`  
3. **Cluster admin** (install operators, create cluster-scoped marketplace subscriptions in operator namespaces).  
4. **Container build** access (e.g. **OpenShift builds** or **podman/docker**) to produce the Spring image.  
5. **Quotas**: modest CPU/memory for one Tempo pod, one collector pod, one app pod.

---

## Deployment option matrix

| Step | Path A — CLI only | Path B — Web console |
|------|-------------------|----------------------|
| Create demo + operator namespaces | **CLI** `oc apply` | **CLI** (recommended) or **Web** (Home → Projects → Create Project) |
| Install Tempo Operator | **CLI** Subscription in `openshift-tempo-operator` | **Web** OperatorHub install |
| Install OpenTelemetry Operator | **CLI** Subscription in `openshift-opentelemetry-operator` | **Web** OperatorHub install |
| Deploy TempoMonolithic + Collector + App | **CLI** `oc apply` | **CLI** `oc apply` (CRs and app YAML) |

**Path B** installs operators from the console; **all custom resources and the application remain declarative YAML** applied with **`oc apply -f`**.

---

## Step-by-step deployment procedure

### Phase 0 — CLI context

```bash
oc whoami
oc version
```

You should see **OpenShift** server version **4.18.x** and a user with sufficient rights.

---

### Path A (full CLI) — Operators and operands

#### Step A1 — **CLI**: Create namespaces

```bash
oc apply -f 00-namespace.yaml
```

#### Step A2 — **CLI**: OperatorGroups for both operator namespaces

```bash
oc apply -f 01-operatorgroup.yaml
```

#### Step A3 — **CLI**: Subscriptions (Tempo + OpenTelemetry)

```bash
oc apply -f 02-subscriptions.yaml
```

#### Step A4 — **CLI**: Wait until ClusterServiceVersions succeed

```bash
oc get csv -n openshift-tempo-operator -w
# Press Ctrl+C when tempo-product reaches Succeeded

oc get csv -n openshift-opentelemetry-operator -w
# Press Ctrl+C when opentelemetry-product reaches Succeeded
```

Quick non-watch check:

```bash
oc get csv -n openshift-tempo-operator
oc get csv -n openshift-opentelemetry-operator
```

#### Step A5 — **CLI**: Deploy Tempo (monolithic, memory backend) + Collector + Spring app

```bash
oc apply -f 10-tempo.yaml
oc apply -f 11-otel-collector.yaml
oc apply -f 20-app-configmap.yaml
oc apply -f 21-app-deployment.yaml
oc apply -f 22-app-service.yaml
oc apply -f 23-app-route.yaml
```

#### Step A6 — **CLI**: Build and push the application image (see [Build and image instructions](#build-and-image-instructions))

After the image is available in **OpenShift internal registry** (example tag used in manifests):

`image-registry.openshift-image-registry.svc:5000/observability-demo/demo-monolith:1.0.0`

rollout:

```bash
oc rollout status deployment/demo-monolith -n observability-demo --timeout=180s
```

---

### Path B — Web console operators, then YAML for everything else

#### Step B1 — **CLI** (recommended): Create namespaces and demo project

Same as Path A:

```bash
oc apply -f 00-namespace.yaml
```

*(Optional **Web**)*: **Administrator** → **Home** → **Projects** → **Create Project** → Name **`observability-demo`**.  
Still create **`openshift-tempo-operator`** and **`openshift-opentelemetry-operator`** via CLI (admins usually prefer CLI for system namespaces).

#### Step B2 — **Web**: Install **Tempo Operator** from OperatorHub

1. Switch to **Administrator** perspective (top-left menu).  
2. **Operators** → **OperatorHub**.  
3. Search: **`Tempo`** or **`Red Hat OpenShift distributed tracing platform`**.  
4. Open the **Tempo Operator** (publisher **Red Hat**).  
5. Click **Install**.  
6. **Installation mode**: **All namespaces on the cluster** (cluster-wide).  
7. **Installed Namespace**: **`openshift-tempo-operator`** (create the project if prompted).  
8. **Update channel**: **`stable`**.  
9. **Approval**: **Automatic**.  
10. **Install** → wait until **Succeeded** on the **Installed Operators** page.

#### Step B3 — **Web**: Install **Red Hat build of OpenTelemetry Operator**

1. **Operators** → **OperatorHub**.  
2. Search: **`Red Hat build of OpenTelemetry`**.  
3. Open **Red Hat build of OpenTelemetry Operator** (publisher **Red Hat**).  
4. Click **Install**.  
5. **Installation mode**: **All namespaces on the cluster**.  
6. **Installed Namespace**: **`openshift-opentelemetry-operator`**.  
7. **Update channel**: **`stable`**.  
8. **Approval**: **Automatic**.  
9. **Install** → verify **Succeeded**.

#### Step B4 — **CLI**: Apply TempoMonolithic, Collector, and application manifests

Even on Path B, apply operands with **`oc`**:

```bash
oc apply -f 10-tempo.yaml
oc apply -f 11-otel-collector.yaml
oc apply -f 20-app-configmap.yaml
oc apply -f 21-app-deployment.yaml
oc apply -f 22-app-service.yaml
oc apply -f 23-app-route.yaml
```

Then build/push the image and wait for rollout (same as Path A Step A6).

---

## Full manifest files

### `00-namespace.yaml`

```yaml
---
apiVersion: v1
kind: Namespace
metadata:
  name: openshift-tempo-operator
  labels:
    openshift.io/cluster-monitoring: "true"
---
apiVersion: v1
kind: Namespace
metadata:
  name: openshift-opentelemetry-operator
  labels:
    openshift.io/cluster-monitoring: "true"
---
apiVersion: v1
kind: Namespace
metadata:
  name: observability-demo
  labels:
    app.kubernetes.io/part-of: observability-demo
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/audit: restricted
    pod-security.kubernetes.io/warn: restricted
```

---

### `01-operatorgroup.yaml`

```yaml
---
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: tempo-operator-group
  namespace: openshift-tempo-operator
spec: {}
---
apiVersion: operators.coreos.com/v1
kind: OperatorGroup
metadata:
  name: opentelemetry-operator-group
  namespace: openshift-opentelemetry-operator
spec: {}
```

---

### `02-subscriptions.yaml`

```yaml
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: tempo-product
  namespace: openshift-tempo-operator
  labels:
    operators.coreos.com/tempo-product.openshift-tempo-operator: ""
spec:
  channel: stable
  installPlanApproval: Automatic
  name: tempo-product
  source: redhat-operators
  sourceNamespace: openshift-marketplace
---
apiVersion: operators.coreos.com/v1alpha1
kind: Subscription
metadata:
  name: opentelemetry-product
  namespace: openshift-opentelemetry-operator
  labels:
    operators.coreos.com/opentelemetry-product.openshift-opentelemetry-operator: ""
spec:
  channel: stable
  installPlanApproval: Automatic
  name: opentelemetry-product
  source: redhat-operators
  sourceNamespace: openshift-marketplace
```

---

### `10-tempo.yaml`

**Note:** This file uses **`TempoMonolithic`** (`kind: TempoMonolithic`) so traces are stored in **memory (tmpfs)** with **no PVC**. A **`TempoStack`** CR is **not** used here because it is intended for **object storage + distributed components** and commonly **PVC-backed ingesters**, which violates this demo’s storage constraints.

```yaml
apiVersion: tempo.grafana.com/v1alpha1
kind: TempoMonolithic
metadata:
  name: demo
  namespace: observability-demo
  labels:
    app.kubernetes.io/name: tempo
    app.kubernetes.io/part-of: observability-demo
spec:
  managementState: Managed
  storage:
    traces:
      backend: memory
      size: 2Gi
  jaegerui:
    enabled: true
    route:
      enabled: true
  extraConfig:
    tempo:
      compactor:
        compaction:
          block_retention: 24h
```

---

### `11-otel-collector.yaml`

The collector **Service** name follows the OpenTelemetry Operator convention **`{metadata.name}-collector`**. With `metadata.name: otel`, the OTLP HTTP endpoint inside the cluster is **`http://otel-collector.observability-demo.svc.cluster.local:4318`**.

```yaml
apiVersion: opentelemetry.io/v1alpha1
kind: OpenTelemetryCollector
metadata:
  name: otel
  namespace: observability-demo
  labels:
    app.kubernetes.io/name: otel-collector
    app.kubernetes.io/part-of: observability-demo
spec:
  mode: deployment
  replicas: 1
  resources:
    limits:
      cpu: 500m
      memory: 512Mi
    requests:
      cpu: 100m
      memory: 128Mi
  config: |
    receivers:
      otlp:
        protocols:
          grpc:
            endpoint: 0.0.0.0:4317
          http:
            endpoint: 0.0.0.0:4318
    processors:
      batch: {}
    exporters:
      otlp/tempo:
        endpoint: tempo-demo.observability-demo.svc.cluster.local:4317
        tls:
          insecure: true
      debug:
        verbosity: detailed
    service:
      pipelines:
        traces:
          receivers: [otlp]
          processors: [batch]
          exporters: [otlp/tempo, debug]
```

**If** your installed collector build rejects the **`debug`** exporter name, replace the `debug` block with:

```yaml
    exporters:
      logging:
        loglevel: debug
```

and change the pipeline exporters list to `[otlp/tempo, logging]`.

---

### `20-app-configmap.yaml`

Optional placeholder for non-secret config. The Deployment uses **environment variables** for OTLP; this ConfigMap can hold **`application.yaml` overrides** if you mount it later. Here it documents the demo and holds a static **banner** file for clarity (no mount required).

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: demo-monolith-info
  namespace: observability-demo
  labels:
    app.kubernetes.io/name: demo-monolith
    app.kubernetes.io/part-of: observability-demo
data:
  README: |
    Demo monolith for OCP 4.18 observability lab.
    Primary endpoint: GET /ui/orders/{id}
    Actuator health: /actuator/health
```

---

### `21-app-deployment.yaml`

**Image:** after you build and push to the internal registry, this reference is valid cluster-internal. For **Path B** builds, adjust only if your image name or tag differs.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: demo-monolith
  namespace: observability-demo
  labels:
    app.kubernetes.io/name: demo-monolith
    app.kubernetes.io/part-of: observability-demo
    app.kubernetes.io/component: application
spec:
  replicas: 1
  selector:
    matchLabels:
      app.kubernetes.io/name: demo-monolith
  template:
    metadata:
      labels:
        app.kubernetes.io/name: demo-monolith
        app.kubernetes.io/part-of: observability-demo
        app.kubernetes.io/component: application
    spec:
      serviceAccountName: default
      securityContext:
        runAsNonRoot: true
        seccompProfile:
          type: RuntimeDefault
      containers:
        - name: app
          image: image-registry.openshift-image-registry.svc:5000/observability-demo/demo-monolith:1.0.0
          imagePullPolicy: Always
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          env:
            - name: SERVER_PORT
              value: "8080"
            - name: MANAGEMENT_OTLP_TRACING_ENDPOINT
              value: "http://otel-collector.observability-demo.svc.cluster.local:4318/v1/traces"
            - name: MANAGEMENT_TRACING_SAMPLING_PROBABILITY
              value: "1.0"
            - name: DEMO_INTERNAL_BASE_URL
              value: "http://127.0.0.1:8080"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: http
              scheme: HTTP
            initialDelaySeconds: 15
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 6
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: http
              scheme: HTTP
            initialDelaySeconds: 30
            periodSeconds: 20
            timeoutSeconds: 3
            failureThreshold: 3
          resources:
            requests:
              cpu: 100m
              memory: 256Mi
            limits:
              cpu: 500m
              memory: 512Mi
          securityContext:
            allowPrivilegeEscalation: false
            capabilities:
              drop:
                - ALL
            readOnlyRootFilesystem: true
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
```

---

### `22-app-service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: demo-monolith
  namespace: observability-demo
  labels:
    app.kubernetes.io/name: demo-monolith
    app.kubernetes.io/part-of: observability-demo
spec:
  selector:
    app.kubernetes.io/name: demo-monolith
  ports:
    - name: http
      port: 8080
      targetPort: http
      protocol: TCP
  type: ClusterIP
```

---

### `23-app-route.yaml`

```yaml
apiVersion: route.openshift.io/v1
kind: Route
metadata:
  name: demo-monolith
  namespace: observability-demo
  labels:
    app.kubernetes.io/name: demo-monolith
    app.kubernetes.io/part-of: observability-demo
spec:
  to:
    kind: Service
    name: demo-monolith
    weight: 100
  port:
    targetPort: http
  tls:
    termination: edge
    insecureEdgeTerminationPolicy: Redirect
```

**Tempo / Jaeger UI Route:** created by the **Tempo Operator** when `spec.jaegerui.route.enabled: true`. Retrieve it with:

```bash
oc get routes -n observability-demo
```

You should see a route similar to **`tempo-demo-jaegerui`** (exact name may vary slightly by operator version; use `oc get route -o wide`).

---

### `30-smoketest.md`

```markdown
# Smoke test — Observability demo (OCP 4.18)

## 1. Application HTTP

```bash
ROUTE=$(oc get route demo-monolith -n observability-demo -o jsonpath='{.spec.host}')
curl -sk "https://${ROUTE}/ui/orders/42"
```

Expected: HTTP 200 and JSON body containing `orderId` **42**.

## 2. Jaeger UI (Tempo query)

1. Open the Jaeger UI route hostname from:

   ```bash
   oc get routes -n observability-demo
   ```

2. Log in with your OpenShift user (oauth-proxy).

3. Select service **`demo-monolith`** (or the service name shown in traces).

4. Find a trace for **`GET /ui/orders/{id}`** and confirm spans:
   - HTTP receive on `/ui/orders/*`
   - `OrderService.getOrder`
   - Client call to `/internal/inventory/*`
   - Server `/internal/inventory/*`
   - (Optional) pricing client/server spans

## 3. Collector logs (debug exporter)

```bash
oc logs -n observability-demo -l app.kubernetes.io/name=otel-collector --tail=80
```

You should see exported span batches in the **debug** (or **logging**) exporter output.

## 4. Tempo monolithic pod

```bash
oc get pods -n observability-demo -l app.kubernetes.io/name=tempo
```

`READY` should be **1/1** (exact labels may vary; use `oc get pods -n observability-demo` if needed).
```

---

## Optional Spring Boot source files

### `spring-monolith/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.4.3</version>
    <relativePath/>
  </parent>

  <groupId>com.example</groupId>
  <artifactId>demo-monolith</artifactId>
  <version>1.0.0</version>
  <name>demo-monolith</name>
  <description>OCP observability demo monolith</description>

  <properties>
    <java.version>21</java.version>
  </properties>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-tracing-bridge-otel</artifactId>
    </dependency>
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-exporter-otlp</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

---

### `spring-monolith/Dockerfile`

```dockerfile
FROM registry.access.redhat.com/ubi9/openjdk-21:latest

WORKDIR /app
COPY target/demo-monolith-1.0.0.jar /app/app.jar

EXPOSE 8080
USER 185

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
```

---

### `spring-monolith/src/main/resources/application.yaml`

```yaml
server:
  port: 8080

spring:
  application:
    name: demo-monolith

management:
  endpoint:
    health:
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,info
  tracing:
    sampling:
      probability: 1.0
  otlp:
    tracing:
      endpoint: http://otel-collector.observability-demo.svc.cluster.local:4318/v1/traces
```

---

### `spring-monolith/src/main/java/com/example/demo/DemoApplication.java`

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {
  public static void main(String[] args) {
    SpringApplication.run(DemoApplication.class, args);
  }
}
```

---

### `spring-monolith/src/main/java/com/example/demo/config/OpenTelemetryConfig.java`

```java
package com.example.demo.config;

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
    return otel.getTracer("demo-monolith");
  }
}
```

---

### `spring-monolith/src/main/java/com/example/demo/web/OrderController.java`

```java
package com.example.demo.web;

import com.example.demo.service.OrderService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OrderController {

  private final OrderService orderService;

  public OrderController(OrderService orderService) {
    this.orderService = orderService;
  }

  @GetMapping("/ui/orders/{id}")
  public Map<String, Object> getOrderUi(@PathVariable String id) {
    return orderService.getOrder(id);
  }
}
```

---

### `spring-monolith/src/main/java/com/example/demo/service/OrderService.java`

```java
package com.example.demo.service;

import com.example.demo.integration.InventoryClient;
import com.example.demo.integration.PricingClient;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

  private final Tracer tracer;
  private final InventoryClient inventoryClient;
  private final PricingClient pricingClient;

  public OrderService(Tracer tracer, InventoryClient inventoryClient, PricingClient pricingClient) {
    this.tracer = tracer;
    this.inventoryClient = inventoryClient;
    this.pricingClient = pricingClient;
  }

  public Map<String, Object> getOrder(String id) {
    Span span = tracer.spanBuilder("OrderService.getOrder").startSpan();
    try (Scope scope = span.makeCurrent()) {
      span.setAttribute("order.id", id);

      boolean inStock = inventoryClient.checkStock(id);
      long priceCents = pricingClient.getPriceCents(id);

      Map<String, Object> body = new HashMap<>();
      body.put("orderId", id);
      body.put("inStock", inStock);
      body.put("priceCents", priceCents);
      body.put("layer", "monolith-ui-to-service-to-integration");
      return body;
    } finally {
      span.end();
    }
  }
}
```

---

### `spring-monolith/src/main/java/com/example/demo/integration/InventoryClient.java`

```java
package com.example.demo.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class InventoryClient {

  private final RestClient restClient;

  public InventoryClient(@Value("${demo.internal-base-url:http://127.0.0.1:8080}") String base) {
    this.restClient = RestClient.builder().baseUrl(base).build();
  }

  public boolean checkStock(String id) {
    return Boolean.parseBoolean(
        restClient
            .get()
            .uri("/internal/inventory/{id}/stock", id)
            .retrieve()
            .body(String.class));
  }
}
```

---

### `spring-monolith/src/main/java/com/example/demo/integration/PricingClient.java`

```java
package com.example.demo.integration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class PricingClient {

  private final RestClient restClient;

  public PricingClient(@Value("${demo.internal-base-url:http://127.0.0.1:8080}") String base) {
    this.restClient = RestClient.builder().baseUrl(base).build();
  }

  public long getPriceCents(String id) {
    String body =
        restClient
            .get()
            .uri("/internal/pricing/{id}/cents", id)
            .retrieve()
            .body(String.class);
    return Long.parseLong(body.trim());
  }
}
```

---

### `spring-monolith/src/main/java/com/example/demo/web/InternalApiController.java`

```java
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
```

**Span expectation:** Spring Boot observability creates **HTTP server spans** for `/ui/orders/{id}` and the internal endpoints, **HTTP client spans** for loopback `RestClient` calls, and a **custom span** `OrderService.getOrder` — typically **five** spans in one trace when context propagation is active.

---

## Build and image instructions

### Option 1 — Local build with **podman** / **docker**, then **OpenShift import**

From `spring-monolith/`:

```bash
mvn -q -DskipTests package
podman build -t demo-monolith:1.0.0 .
```

Log in to the internal registry and push (replace `default` project path if you use another user):

```bash
oc registry login --registry="$(oc get route default-route -n openshift-image-registry -o jsonpath='{.spec.host}' 2>/dev/null)" || true
# If your cluster uses the internal service DNS from a bastion with oc login:
INTERNAL_REGISTRY=$(oc get configs.imageregistry.operator.openshift.io/cluster -o jsonpath='{.status.internalRegistryHostname}')
podman tag demo-monolith:1.0.0 "${INTERNAL_REGISTRY}/observability-demo/demo-monolith:1.0.0"
podman push "${INTERNAL_REGISTRY}/observability-demo/demo-monolith:1.0.0"
```

### Option 2 — **OpenShift build** (Binary build)

```bash
oc project observability-demo
oc new-build --name=demo-monolith --binary --strategy=docker --dockerfile-path=Dockerfile
# from spring-monolith directory after mvn package:
oc start-build demo-monolith --from-dir=. --follow
oc tag demo-monolith:latest demo-monolith:1.0.0
```

Ensure **`21-app-deployment.yaml`** references the resulting **ImageStreamTag** or internal registry image you tagged.

---

## Verification steps

### 1) Operators installed

```bash
oc get csv -n openshift-tempo-operator
oc get csv -n openshift-opentelemetry-operator
oc get installplan -n openshift-tempo-operator
oc get installplan -n openshift-opentelemetry-operator
```

**Expected:** CSVs **`tempo-product`** and **`opentelemetry-product`** in phase **`Succeeded`**.

### 2) Tempo custom resource ready

```bash
oc get tempomonolithic -n observability-demo
oc describe tempomonolithic demo -n observability-demo
```

**Expected:** A **Ready** condition (wording may be `Ready` / `Available` depending on operator version) and a running Tempo pod.

### 3) OpenTelemetry Collector ready

```bash
oc get opentelemetrycollector -n observability-demo
oc get deploy -n observability-demo -l app.kubernetes.io/name=otel-collector
oc get pods -n observability-demo -l app.kubernetes.io/name=otel-collector
```

**Expected:** Deployment **Available**, pod **Running**.

If labels differ:

```bash
oc get pods -n observability-demo | grep otel
```

### 4) Generate application traffic

```bash
HOST=$(oc get route demo-monolith -n observability-demo -o jsonpath='{.spec.host}')
curl -sk "https://${HOST}/ui/orders/7"
```

### 5) Confirm traces reach the collector

```bash
oc logs -n observability-demo -l app.kubernetes.io/name=otel-collector --tail=100
```

**Expected:** Debug/logging output showing **spans** or **ResourceSpans** for service **`demo-monolith`**.

### 6) Confirm traces in Tempo (CLI-friendly)

Jaeger UI is simplest (browser). For **API-style** checks without resolving OAuth in `curl`, port-forward the query frontend service (name may vary; discover with):

```bash
oc get svc -n observability-demo | grep -E 'tempo|jaeger|query'
```

Example pattern for monolithic deployments (adjust service name to your cluster):

```bash
# Replace SERVICE if your cluster lists a different tempo query service
SERVICE=tempo-demo-query-frontend
oc port-forward -n observability-demo "svc/${SERVICE}" 3200:3200
```

In another terminal:

```bash
curl -s "http://127.0.0.1:3200/api/services" | head
```

**Expected:** JSON listing services including **`demo-monolith`**.

---

## Expected trace flow

1. **Edge / frontend:** `GET /ui/orders/{id}` → **HTTP server span** (Spring MVC / Tomcat instrumentation).  
2. **Business:** **`OrderService.getOrder`** → **manual span** (custom business layer).  
3. **Integration (inventory):** **HTTP client** `GET /internal/inventory/{id}/stock` → **HTTP server** on the same pod (loopback) → **two spans** (client + server).  
4. **Integration (pricing):** **HTTP client** `GET /internal/pricing/{id}/cents` → **HTTP server** → **two more spans** (bonus layer).

**Minimum interpretable layers for the demo:** (1) UI HTTP, (2) `OrderService.getOrder`, (3) inventory client/server pair as “backend integration.”

---

## Troubleshooting

| Symptom | Likely cause | What to do |
|--------|--------------|------------|
| Subscription not installing | Disconnected cluster / missing `redhat-operators` | Install a **CatalogSource** mirroring Red Hat operators or use a connected cluster. `oc get packagemanifest -n openshift-marketplace \| grep tempo` |
| CSV stuck | Insufficient permissions or conflicting OperatorGroup | `oc describe subscription -n openshift-tempo-operator tempo-product` |
| `TempoMonolithic` not created | CRD not installed yet | Wait for CSV **Succeeded**; `oc get crd \| grep tempo` |
| Collector pod crash loop | `debug` exporter unsupported | Swap **`debug`** for **`logging`** exporter (see `11-otel-collector.yaml` note). |
| No traces in Tempo | Wrong Tempo endpoint | Verify **`tempo-demo.observability-demo.svc.cluster.local:4317`** resolves: `oc get svc -n observability-demo` |
| App cannot reach collector | Wrong service name | Must target **`otel-collector`** Service (from collector name **`otel`**). `oc get svc otel-collector -n observability-demo` |
| App `ImagePullBackOff` | Image not pushed to expected name/tag | `oc describe pod -l app.kubernetes.io/name=demo-monolith -n observability-demo` |
| Read-only root filesystem errors | App writes outside `/tmp` | Only `/tmp` is writable in the Deployment; keep Spring temp dir on `/tmp` (default `java.io.tmpdir` uses `/tmp`). |
| Jaeger UI 403 / OAuth | Expected for Route | Log in with cluster credentials; use **`oc port-forward`** for raw HTTP API testing. |

**NetworkPolicy:** If your cluster enforces strict policies and traffic is blocked, inspect Tempo operator-generated **NetworkPolicy** objects in **`observability-demo`** and adjust according to your organization’s standards (not shown here to avoid over-broad rules).

---

## Cleanup

### Remove demo workloads

```bash
oc delete -f 23-app-route.yaml --ignore-not-found
oc delete -f 22-app-service.yaml --ignore-not-found
oc delete -f 21-app-deployment.yaml --ignore-not-found
oc delete -f 20-app-configmap.yaml --ignore-not-found
oc delete -f 11-otel-collector.yaml --ignore-not-found
oc delete -f 10-tempo.yaml --ignore-not-found
```

### Remove demo namespace (optional)

```bash
oc delete project observability-demo
```

### Remove OpenTelemetry operator subscription

```bash
oc delete -f 02-subscriptions.yaml --ignore-not-found
oc delete subscription opentelemetry-product -n openshift-opentelemetry-operator --ignore-not-found
oc delete csv -n openshift-opentelemetry-operator -l operators.coreos.com/opentelemetry-product.openshift-opentelemetry-operator
```

### Remove Tempo operator subscription

```bash
oc delete subscription tempo-product -n openshift-tempo-operator --ignore-not-found
oc delete csv -n openshift-tempo-operator -l operators.coreos.com/tempo-product.openshift-tempo-operator
```

### Remove operator namespaces (optional, cluster housekeeping)

```bash
oc delete -f 01-operatorgroup.yaml --ignore-not-found
oc delete project openshift-opentelemetry-operator
oc delete project openshift-tempo-operator
```

**Note:** Deleting operator namespaces removes **all** operands managed by those operators cluster-wide. Only do this if you understand the impact.

---

## Apply All (Path A — exact `oc apply -f` order)

```bash
oc apply -f 00-namespace.yaml
oc apply -f 01-operatorgroup.yaml
oc apply -f 02-subscriptions.yaml
# wait for both CSVs Succeeded
oc apply -f 10-tempo.yaml
oc apply -f 11-otel-collector.yaml
oc apply -f 20-app-configmap.yaml
oc apply -f 21-app-deployment.yaml
oc apply -f 22-app-service.yaml
oc apply -f 23-app-route.yaml
# build/push image, then:
oc rollout status deployment/demo-monolith -n observability-demo --timeout=180s
```

---

## Web Console Install Path (summary)

### Tempo Operator (OperatorHub)

1. **Administrator** perspective.  
2. **Operators** → **OperatorHub**.  
3. Search **`Tempo`** / **distributed tracing**.  
4. Choose **Tempo Operator** (**Red Hat**).  
5. **Install** → **All namespaces on the cluster** → installed namespace **`openshift-tempo-operator`** → channel **`stable`** → approval **Automatic** → **Install**.

### Red Hat build of OpenTelemetry Operator (OperatorHub)

1. **Operators** → **OperatorHub**.  
2. Search **`Red Hat build of OpenTelemetry`**.  
3. **Install** → **All namespaces** → **`openshift-opentelemetry-operator`** → channel **`stable`** → **Automatic** → **Install**.

### Post-install custom resources (still YAML/CLI)

- Create **`TempoMonolithic`** in **`observability-demo`**: `oc apply -f 10-tempo.yaml`  
- Create **`OpenTelemetryCollector`**: `oc apply -f 11-otel-collector.yaml`  
- Deploy app: `oc apply -f 20-app-configmap.yaml` … `23-app-route.yaml`  
- Build/push image and wait for **`demo-monolith`** rollout.

### What remains better as YAML after operators

- **`TempoMonolithic`** / **`OpenTelemetryCollector`** (repeatable, Git-friendly).  
- **Application `Deployment`/`Service`/`Route`** (GitOps-ready).  
- **Labels, resource requests/limits, probes**, and **Routes** for the app.

---

## Cluster-scoped vs namespace-scoped (reference)

| Resource | Scope | Namespace / notes |
|----------|-------|-------------------|
| `Namespace` | cluster | `openshift-tempo-operator`, `openshift-opentelemetry-operator`, `observability-demo` |
| `OperatorGroup` | namespaced | operator install namespaces |
| `Subscription` | namespaced | operator install namespaces |
| `ClusterServiceVersion` | namespaced | created by OLM in operator namespaces |
| `TempoMonolithic` | namespaced | `observability-demo` |
| `OpenTelemetryCollector` | namespaced | `observability-demo` |
| `Deployment`/`Service`/`Route` | namespaced | `observability-demo` |

No **Helm**, **Argo CD**, **PVC**, **StatefulSet-with-PVC**, or **PV** objects are used in this package.

---

*Document generated for educational / lab use on **OpenShift Container Platform 4.18**. Operator CRD field names and exact Route service names may vary slightly by z-stream; use `oc get` / `oc explain` as the authoritative check on your cluster.*
