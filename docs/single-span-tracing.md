# Single-span tracing mode (`single-span` branch)

This branch treats **frontend-ui** and **backend-app** as **one logical application** in Jaeger:

- **Same trace ID** end-to-end for one UI action (unchanged contract).
- **Same OpenTelemetry `service.name`:** `order-demo-app` on both pods.
- **Span model** (Get Order example): root **SERVER** `app: user click get order` → **INTERNAL** `frontend: prepare request` → **CLIENT** HTTP to API → **SERVER** `GET /api/orders/...` → **INTERNAL** `backend: load order` → **CLIENT** `postgres: SELECT orders`.

Inventory and pricing actions use `app: user click check inventory` / `app: user click calculate price` and matching backend INTERNAL names.

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
3. Open one trace → confirm **one trace ID** and the span hierarchy described above.
