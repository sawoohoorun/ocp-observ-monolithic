# Java auto-instrumentation (`auto-instrumentation` branch)

This branch follows Red Hat’s guide **[How to use auto-instrumentation with OpenTelemetry](https://developers.redhat.com/articles/2026/02/25/how-use-auto-instrumentation-opentelemetry)**: the **Red Hat build of OpenTelemetry Operator** injects the **OpenTelemetry Java agent** into **`frontend-ui`** and **`backend-app`** at pod admission time — **no** Micrometer OTLP bridge and **no** hand-written spans in the application source.

## What was added

| Artifact | Role |
|----------|------|
| **`openshift/12-instrumentation.yaml`** | **`Instrumentation`** CR named **`demo-java`**: OTLP HTTP endpoint, **`OTEL_SERVICE_NAME=order-demo-app`**, **`OTEL_SEMCONV_STABILITY_OPT_IN=http`**, propagators **`tracecontext`** + **`baggage`**, **parentbased_traceidratio** sampler at **1.0**. |
| **Pod annotations** on **`21-ui-deployment.yaml`** and **`31-backend-deployment.yaml`** | `instrumentation.opentelemetry.io/inject-java: demo-java` and `instrumentation.opentelemetry.io/container-names: app` so the **app** container is instrumented. |

## Prerequisites on the cluster

- **Red Hat build of OpenTelemetry** operator installed (**`02-subscriptions.yaml`**).
- **cert-manager** available (operator webhooks; see the [Red Hat article](https://developers.redhat.com/articles/2026/02/25/how-use-auto-instrumentation-opentelemetry)).
- **`OpenTelemetryCollector`** applied (**`11-otel-collector.yaml`**) so Service **`otel-collector`** resolves in **`observability-single-span`**.

## Apply order

After **`11-otel-collector.yaml`**:

```bash
oc apply -f openshift/12-instrumentation.yaml
```

Then apply or roll out **`21-ui-deployment.yaml`** and **`31-backend-deployment.yaml`**.

## Verify injection

```bash
oc get instrumentation -n observability-single-span
oc describe pod -n observability-single-span -l app.kubernetes.io/name=frontend-ui | head -80
```

Confirm the **app** container command/args include **`-javaagent:`** and env vars such as **`OTEL_EXPORTER_OTLP_ENDPOINT`** and **`OTEL_SERVICE_NAME`**.

## Verify traces

1. Open the **`frontend-ui`** Route → **Get Order** once.
2. Open **Jaeger** (Tempo-backed route in the same project).
3. **Service** = **`order-demo-app`** → open a trace.
4. Expect **one trace ID** with **HTTP** spans on the UI pod, an **HTTP client** span for the API call, **HTTP server** spans on the API pod, and **JDBC** / database-related spans from the agent.

Span **names** will follow the **Java agent** and **stable HTTP/DB** semantic conventions — not the custom **`app:`** / **`step:`** / **`db: select …`** names from the **`single-span`** branch.

## Git and builds

- **BuildConfigs** use **`ref: auto-instrumentation`**.
- **Tekton** default revision parameter is **`auto-instrumentation`**.

## Comparison: `single-span` vs `auto-instrumentation`

| | **`single-span`** | **`auto-instrumentation`** |
|---|-------------------|----------------------------|
| Tracing | Micrometer + OTLP + manual / convention spans in code | Operator-injected **Java agent** only |
| Custom operation names | Yes (`app: get order`, `step: …`, etc.) | No — agent defaults + semconv |
| OTLP from app | Spring **`management.otlp.tracing`** | **`Instrumentation`** → agent env |
