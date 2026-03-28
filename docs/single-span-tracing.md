# Single-span tracing mode (`single-span` branch)

This branch treats **frontend-ui** and **backend-app** as **one logical application** in Jaeger:

- **Same trace ID** end-to-end for one UI action (unchanged contract).
- **Same OpenTelemetry `service.name`:** `order-demo-app` on both pods.
- **Span model** (Get Order example): root **SERVER** `app: user click get order` → **INTERNAL** `frontend: prepare request` → **CLIENT** HTTP to API → **SERVER** `GET /api/orders/...` → **INTERNAL** `backend: load order` → **CLIENT** `postgres: SELECT orders`.

Inventory and pricing actions use `app: user click check inventory` / `app: user click calculate price` and matching backend INTERNAL names.

## Deploy

Rebuild both images from this branch (`BuildConfig` Git ref = `single-span` or merge to your remote), roll out Deployments. No extra env vars are required; `application.yaml` sets `order-demo-app`.

## Verify in Jaeger

1. Open the UI route → **Get Order** once.
2. Jaeger → **Service** = **`order-demo-app`** (single service for UI + API spans).
3. Open one trace → confirm:
   - Root / entry span name **`app: user click get order`** (kind **SERVER**).
   - Child **`frontend: prepare request`** (**INTERNAL**).
   - **CLIENT** span to **`backend-app`** service host (HTTP).
   - **SERVER** span for **`GET /api/orders/...`** (same **`order-demo-app`** process tag on backend).
   - **`backend: load order`** (**INTERNAL**).
   - **`postgres: SELECT orders`** (**CLIENT**, `db.system=postgresql`).
4. All spans share **one trace ID**.
