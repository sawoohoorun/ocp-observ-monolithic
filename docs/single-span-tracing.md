# Single-span tracing mode (`single-span` branch)

This branch treats **frontend-ui** and **backend-app** as **one logical application** in Jaeger:

- **Same trace ID** end-to-end for one UI action.
- **Same OpenTelemetry `service.name`:** `order-demo-app` on both pods.
- **Same root operation** per user action: the UI **SERVER** span uses **`app: get order`**, **`app: check inventory`**, or **`app: calculate price`** (not separate “frontend” vs “backend” top-level operation names).

## Span model (Get Order)

Conceptual waterfall:

```text
SERVER    app: get order
  INTERNAL  step: prepare request
  CLIENT    HTTP GET /api/orders/...   (RestClient; name may follow Micrometer defaults)
  SERVER    step: process order
  CLIENT    db: select orders
```

Inventory and pricing follow the same pattern: root **`app: check inventory`** / **`app: calculate price`**, then **`step: prepare request`**, API **SERVER** **`step: check inventory`** / **`step: calculate price`**, and **CLIENT** **`db: select inventory`** / **`db: select pricing`**.

Backend inbound HTTP contextual names come from **`ApiServerObservationConfig`** (`ServerRequestObservationConvention`). UI roots come from **`UiServerObservationConfig`**.

## Namespace

All **`openshift/`** and **`tekton/`** manifests on this branch target **`observability-single-span`** — a **separate** OpenShift project from **`observability-demo`** so you can run both demos on one cluster (different namespaces and `ImageStream`s).

Create the project from **`openshift/00-namespace.yaml`**, then apply the rest of **`openshift/`** in the usual order. Use **`-n observability-single-span`** on **`oc`** / **`tkn`** commands.

**BuildConfigs** use Git **`ref: single-span`**. Push this branch to your remote before starting builds.

## Deploy

```bash
oc apply -f openshift/00-namespace.yaml
# … operators, Tempo, collector, postgres, BCs, services, deployments (see README apply order)
oc start-build backend-app -n observability-single-span --follow
oc start-build frontend-ui -n observability-single-span --follow
```

No extra env vars are required; `application.yaml` uses in-namespace DNS (`*.observability-single-span.svc.cluster.local`).

## Verify in Jaeger

1. Open the UI route in **`observability-single-span`** → **Get Order** once.
2. Jaeger route in the **same** project → **Service** = **`order-demo-app`**.
3. Open one trace → confirm **one trace ID**, root **SERVER** **`app: get order`**, and children **`step: prepare request`**, **`step: process order`**, **`db: select orders`** (plus any Micrometer-named HTTP client span).
