# OpenShift 4.18 Observability Demo — 3-Layer App, Tempo, Collector & Jaeger UI

Guide to deploy a **three-layer traceable demo** on **OpenShift Container Platform 4.18**: **frontend UI** (Spring MVC + Thymeleaf), **backend monolith** (Spring Boot + JPA + PostgreSQL), **PostgreSQL** (ephemeral `emptyDir` only), plus **TempoMonolithic**, **Red Hat build of OpenTelemetry Collector**, and **Jaeger UI** (query against Tempo). Application images are built **in-cluster** via **`BuildConfig`** + Git. **No Helm, no Argo CD, no PVCs.**

Manifests live in **`openshift/`** in [github.com/sawoohoorun/ocp-observ-monolithic](https://github.com/sawoohoorun/ocp-observ-monolithic). This document describes **purpose**, **order of apply**, and **commands** — not full YAML dumps.

---

## Architecture Summary

```mermaid
flowchart LR
  B[Browser]
  F[frontend-ui Deployment]
  A[backend-app Deployment]
  P[(PostgreSQL emptyDir)]
  C[OTel Collector]
  T[Tempo monolithic]
  J[Jaeger UI Route]
  B -->|HTTPS Route| F
  F -->|HTTP + traceparent| A
  A -->|JDBC| P
  F -->|OTLP HTTP :4318| C
  A -->|OTLP HTTP :4318| C
  C -->|OTLP gRPC| T
  B -->|HTTPS OAuth| J
  J -->|Query API| T
```

- **Two OpenTelemetry producers** (`frontend-ui`, `backend-app`) → same **Collector** → **Tempo** → **Jaeger UI** (Tempo-backed query).
- **PostgreSQL** stores demo rows only; traces are **not** in Postgres.

---

## Upgraded 3-Layer Application Architecture

| Layer | Workload | Responsibility |
|-------|----------|----------------|
| **1 — Frontend UI** | `frontend-ui` | Serves HTML with **Get Order / Check Inventory / Calculate Price** actions. Each action performs a **server-side** `RestClient` call to `backend-app` so **W3C `traceparent`** propagates and **one trace ID** spans UI + API + DB (**distinct `service.name`** per tier — see **Same Trace ID Across Frontend and Backend**). |
| **2 — Backend monolith** | `backend-app` | REST `/api/...`, **controller** spans (auto), **service** spans (manual + business logic), **database client** spans (manual `SpanKind.CLIENT` around JPA reads + `db.*` attributes). |
| **3 — Data** | `postgres` | Single non-HA **Deployment**, **`emptyDir`** data volume, **init SQL** via ConfigMap → `docker-entrypoint-initdb.d`. |

---

## Frontend UI Design

- **Stack:** Spring Boot **3.4**, **Thymeleaf** (server-side rendering — no browser JS tracing required for the demo).
- **Service name:** `spring.application.name: frontend-ui` → **`service.name`** in exported traces.
- **Outbound calls:** `RestClient` to `demo.backend-base-url` (overridden in-cluster by **`DEMO_BACKEND_BASE_URL`** → `http://backend-app.observability-demo.svc.cluster.local:8080`).
- **User actions:** Links such as `/action/order/ORD-001`, `/action/inventory/SKU-100`, `/action/pricing/SKU-100` each trigger one backend round-trip and **one distributed trace** spanning UI + API + DB.

---

## Backend Monolith Design

- **Stack:** Spring Web, Actuator, **Data JPA**, **PostgreSQL** driver, **Micrometer tracing bridge → OTLP**.
- **Service name:** `backend-app`.
- **APIs:** `GET /api/orders/{id}`, `GET /api/inventory/{sku}`, `GET /api/pricing/{sku}`.
- **Layers:** `ApiController` → `OrderService` / `InventoryService` / `PricingService` → JPA repositories → PostgreSQL.
- **DSN:** `jdbc:postgresql://postgres.observability-demo.svc.cluster.local:5432/demodb` with password from **`DB_PASSWORD`** (Secret).

---

## PostgreSQL Demo Layer

- **Image:** `docker.io/library/postgres:16-alpine` (if your cluster blocks Docker Hub, mirror or substitute a Red Hat–compatible image and update **`openshift/40-postgres.yaml`**).
- **Storage:** **`emptyDir`** only at `/var/lib/postgresql/data` — data is **lost** when the pod is deleted.
- **Bootstrap:** ConfigMap **`postgres-init`** mounted as **`/docker-entrypoint-initdb.d`**; creates `orders`, `inventory`, `pricing` and seeds **`ORD-001`**, **`SKU-100`**, etc.
- **Credentials:** Secret **`postgres-secret`** (referenced by Deployment and backend).

---

## Cross-Service Trace Propagation

1. **Browser → frontend-ui:** vanilla HTTP GET; Tomcat/Spring creates the **root server span** for `/action/...` on **`frontend-ui`**.
2. **frontend-ui → backend-app:** **`RestClient`** runs in the same request thread; Spring Boot observability injects **`traceparent`** (W3C) on the outbound request. The backend’s Tomcat creates a **child server span** linked to the same trace.
3. **backend-app internal:** Service-layer manual spans and DB client spans attach **under** the backend HTTP span.
4. **Export:** Both apps send OTLP **HTTP** to **`http://otel-collector.observability-demo.svc.cluster.local:4318/v1/traces`**.

**Correlation contract:** One user action MUST yield **one trace ID** spanning **`frontend-ui`** and **`backend-app`** (distinct services in Jaeger). Full semantics, headers, and failure modes are in **Same Trace ID Across Frontend and Backend** below.

---

## Same Trace ID Across Frontend and Backend

**Tracing consistency requirement (default):** When a user opens the UI and triggers an action such as **Get Order**, the **entire path** MUST stay in **one distributed trace**: **`frontend-ui`** spans and **`backend-app`** spans share the **same trace ID**. In Jaeger they MUST appear as **different services** — preferred resource names **`frontend-ui`** and **`backend-app`** — not merged into one service unless you deliberately follow **Optional: Force Frontend and Backend to Appear as the Same Service**. **PostgreSQL-related client spans** (e.g. **`SELECT orders`**) MUST remain in that **same trace**, typically as descendants of the backend HTTP span.

**Implementation requirement:** Application code and runtime configuration MUST preserve **W3C Trace Context** end to end on the **frontend → backend** hop (at minimum **`traceparent`** injection on the outbound request and extraction on the inbound request). Any change that drops, rewrites, or bypasses that propagation breaks **single-trace** behavior.

### 1. How the trace starts in the frontend

This demo uses **server-side rendering (Thymeleaf)**. The browser performs a normal **HTTP GET** to **`frontend-ui`** (for example `/action/order/ORD-001`). **Distributed tracing starts in the frontend pod** (not in the browser): Spring Web / embedded Tomcat + Micrometer Observability create an **inbound HTTP server span** for that request, tagged with the **`frontend-ui`** OpenTelemetry **resource** / **`service.name`**.

### 2. How trace context is propagated from frontend to backend

The UI controller invokes **`RestClient`** to call **`backend-app`** during **the same synchronous request** that is already traced. Spring Boot **3.x** with **`micrometer-tracing-bridge-otel`** propagates the **current trace context** onto the outbound HTTP client call, so the child spans on the backend attach to the **same trace** as the frontend’s inbound span.

### 3. Headers: `traceparent`, `tracestate`, and `baggage`

| Mechanism | Role in this demo |
|-----------|-------------------|
| **`traceparent`** (W3C Trace Context) | **Required** on the wire for correlation. Carries **trace id**, **parent span id**, and **sampling** flags. The default **W3C propagator** injects it on **`RestClient`** and the backend server instrumentation **extracts** it. |
| **`tracestate`** | **Optional** list of vendor-specific entries. May appear alongside **`traceparent`**. Its absence does **not** by itself indicate broken propagation. |
| **W3C Baggage** | **Not used** in the default configuration. **Baggage** is for carrying arbitrary key/value context across services; it is separate from the trace id. You would only see baggage headers if you add an explicit **baggage propagator** — not required for **same trace ID**. |

### 4. How the backend continues the existing trace instead of creating a new one

**`backend-app`** Tomcat/Spring creates an **HTTP server span** for `/api/...` using the **extracted** context from the incoming request. That span’s **parent** is the propagated client span from **`frontend-ui`**, so the **trace id** matches. **Controller**, **service**, and **manual DB client** spans are created with the **same active context**, so they are **children** (direct or indirect) of that backend server span — **not** a new root trace.

### 5. How Jaeger displays spans from multiple services in one trace

In **Jaeger UI** (backed by **Tempo** here), opening a **trace** shows **all spans** for one **trace ID** in a single timeline / waterfall. Each span still shows its **service** (process) — e.g. **`frontend-ui`** for UI spans and **`backend-app`** for API and DB-related spans. **Multiple services inside one trace** is the **expected** shape for distributed tracing.

### 6. Why same trace ID does not require the same service name

**Trace ID** = one logical distributed operation. **`service.name`** = which component emitted a span. You want **one id** and **two (or more) services** so dependency and service filters stay meaningful. Collapsing both into one **`service.name`** is **optional** and generally **discouraged** (see below).

### 7. Troubleshooting when frontend and backend create separate traces

Use **Troubleshooting — distributed trace correlation** in the main **Troubleshooting** table. Typical causes: missing **`traceparent`** (stripped proxy, wrong client), **async** work without context propagation, **direct browser → backend** calls without a traced frontend hop, or **sampling** / exporter misconfiguration.

---

## Optional: Force Frontend and Backend to Appear as the Same Service

**Default remains:** **same trace ID**, **different** **`service.name`** values (**`frontend-ui`** / **`backend-app`**), **one** trace including DB spans.

If you **explicitly** want both tiers to show as **one service** in Jaeger (e.g. a simplified screenshot), you can set the **same** OpenTelemetry **`service.name`** resource in **both** applications — for example align **`spring.application.name`** (and any OTLP resource overrides) to a single value in **`frontend-ui`** and **`backend-app`**.

**Tradeoffs (why this is usually NOT recommended):**

- **Jaeger** and service graphs **no longer separate** frontend vs backend ownership.
- **Service dependency** and **error attribution** views become **less useful**.
- **On-call and troubleshooting** are harder because span process labels no longer match deployment boundaries.
- **Distributed boundaries** (UI vs API) are **hidden** even though the trace id may still be correct.

Use this **only** as a **demo shortcut**; revert to **distinct** **`service.name`** values for realistic observability.

---

## Database Span Visibility in Jaeger

- **Approach:** **Manual OpenTelemetry spans** (`SpanKind.CLIENT`) named e.g. **`SELECT orders`**, **`SELECT inventory`**, **`SELECT pricing`**, with attributes **`db.system=postgresql`**, **`db.statement`**, **`db.sql.table`**.
- **Why manual:** Guarantees a **visible SQL-layer span** in Jaeger for lab validation. Pure JPA/Micrometer JDBC auto-instrumentation varies by version; if those spans appear **in addition**, they are a bonus.
- **Missing DB spans:** If you see backend HTTP + service spans but **no** `SELECT *` client spans, check **`OrderService`** / **`InventoryService`** / **`PricingService`** in `backend-app` or verify the deployment is running the expected image.

---

## OCP 4.18 Assumptions

- Connected cluster, **`redhat-operators`**, **OperatorHub** for Path B.
- **`cluster-admin`** for operators and **`observability-demo`**.
- Build pods: Git + Maven Central; app pods: pull **internal registry** + optional **docker.io** for Postgres.
- **Restricted** PSA on `observability-demo` (manifests use non-root, dropped caps, `emptyDir` `/tmp` for Java apps).

---

## Existing Source Code Assessment

The repository is the application source:

- **`frontend-ui/`** — Thymeleaf UI + `RestClient` to backend.
- **`backend-app/`** — REST API, JPA entities/repositories, traced services.
- **`openshift/`** — All deploy/build/operator operand YAML for this demo.
- **`tekton/`** — OpenShift Pipelines Tasks, `Pipeline`, sample `PipelineRun`, and **`tekton/README.md`** (CI/CD).

The previous single **`spring-monolith`** tree was **removed** in favor of this split.

---

## Repository Structure Summary

```text
├── backend-app/           # Maven, Spring Boot API + JPA
├── frontend-ui/           # Maven, Spring Boot + Thymeleaf
├── openshift/             # Namespaces, operators, Tempo, Collector, Postgres, builds, Deployments, Routes
├── tekton/                # Tekton pipeline + tasks (optional automation)
├── OCP-4.18-Observability-Demo-Deployment.md
└── README.md              # kept in sync with this guide
```

---

## What Changed in the Application Code

| Area | Change | Why |
|------|--------|-----|
| **Layout** | Replaced single `spring-monolith` with **`frontend-ui/`** + **`backend-app/`** | Separate deployments, routes, and **`service.name`** values for Jaeger. |
| **Manifests** | Moved to **`openshift/`** with numbered files (`20`–`40`) | Matches OpenShift-focused layout you requested. |
| **Backend** | JPA + Postgres + manual **DB client spans** | Satisfy **Layer 3** visibility in Jaeger. |
| **Frontend** | New Thymeleaf app + **`RestClient`** | **Layer 1** server span + **HTTP client** span to backend with **W3C** propagation; **one trace ID** with **`backend-app`** (see **Same Trace ID Across Frontend and Backend**). |
| **Postgres** | New `40-postgres.yaml` | Ephemeral DB with seed data; **no PVC**. |

---

## File Tree

```text
openshift/
├── 00-namespace.yaml
├── 01-operatorgroup.yaml
├── 02-subscriptions.yaml
├── 10-tempo.yaml
├── 11-otel-collector.yaml
├── 20-ui-buildconfig.yaml      # ImageStream + BuildConfig (frontend-ui)
├── 21-ui-deployment.yaml
├── 22-ui-service.yaml
├── 23-ui-route.yaml
├── 30-backend-buildconfig.yaml # ImageStream + BuildConfig (backend-app)
├── 31-backend-deployment.yaml
├── 32-backend-service.yaml
├── 40-postgres.yaml            # Secret, ConfigMap, Service, Deployment
└── 30-smoketest.md
```

---

## File Purpose Summary

| File | Purpose |
|------|---------|
| `00-namespace.yaml` | Operator + `observability-demo` namespaces. |
| `01-operatorgroup.yaml` | OperatorGroups for Tempo + OpenTelemetry operators. |
| `02-subscriptions.yaml` | `tempo-product`, `opentelemetry-product` subscriptions. |
| `10-tempo.yaml` | `TempoMonolithic` + **Jaeger UI Route** + memory storage. |
| `11-otel-collector.yaml` | `OpenTelemetryCollector` OTLP in → Tempo gRPC out. |
| `20-ui-buildconfig.yaml` | Git `contextDir: frontend-ui` → image **`frontend-ui:1.0.0`**. |
| `21–23` | UI Deployment (OTLP + backend URL env), Service, **edge Route** (user-facing). |
| `30-backend-buildconfig.yaml` | Git `contextDir: backend-app` → **`backend-app:1.0.0`**. |
| `31–32` | Backend Deployment (`DB_PASSWORD` from Secret), ClusterIP **Service** (cluster-only). |
| `40-postgres.yaml` | Postgres **emptyDir**, init schema/data, **Secret** password. |

**Edit if needed:** `spec.source.git` in both `BuildConfig`s for forks/branches; Postgres **image** in `40-postgres.yaml` if Docker Hub is blocked.

---

## Prerequisites

- `oc` CLI, cluster-admin (install), pull secrets if using private registries.
- Resource headroom: Tempo + Collector + Postgres + 2 Java pods + build pods.

---

## Deployment Option Matrix

| Step | Path A — CLI | Path B — Console |
|------|--------------|------------------|
| Operators | `oc apply -f openshift/00–02` | OperatorHub → Tempo + OpenTelemetry |
| Operands + apps | `oc apply` + `oc start-build` | Same after CSV **Succeeded** |

---

## Operator Installation Steps

```bash
oc apply -f openshift/00-namespace.yaml
oc apply -f openshift/01-operatorgroup.yaml
oc apply -f openshift/02-subscriptions.yaml
oc get csv -n openshift-tempo-operator
oc get csv -n openshift-opentelemetry-operator
```

Wait for **`Succeeded`**.

---

## Observability Stack Deployment Steps

```bash
oc apply -f openshift/10-tempo.yaml
oc apply -f openshift/11-otel-collector.yaml
```

Jaeger UI is enabled on **`TempoMonolithic`** (`spec.jaegerui.enabled` + `route.enabled`). List routes: `oc get routes -n observability-demo`.

---

## Application Build Steps Using OpenShift Build / S2I

**Strategy:** **Docker** `BuildConfig` + Git (multi-stage `Dockerfile` + Maven Wrapper in each app) — same rationale as before: **Java 21**, reproducible builds, no pre-committed `target/`.

1. **PostgreSQL first** (schema before backend):

   ```bash
   oc apply -f openshift/40-postgres.yaml
   oc rollout status deployment/postgres -n observability-demo --timeout=300s
   ```

2. **Register builds & start** (order flexible; backend is often built first):

   ```bash
   oc apply -f openshift/30-backend-buildconfig.yaml
   oc apply -f openshift/20-ui-buildconfig.yaml
   oc start-build backend-app -n observability-demo --follow
   oc start-build frontend-ui -n observability-demo --follow
   ```

3. **Inspect:**

   ```bash
   oc get builds -n observability-demo
   oc describe is backend-app -n observability-demo
   oc describe is frontend-ui -n observability-demo
   ```

---

## Application Deployment Steps

```bash
oc apply -f openshift/32-backend-service.yaml
oc apply -f openshift/31-backend-deployment.yaml
oc rollout status deployment/backend-app -n observability-demo --timeout=300s

oc apply -f openshift/22-ui-service.yaml
oc apply -f openshift/23-ui-route.yaml
oc apply -f openshift/21-ui-deployment.yaml
oc rollout status deployment/frontend-ui -n observability-demo --timeout=300s

oc get route frontend-ui -n observability-demo -o jsonpath='{.spec.host}{"\n"}'
```

Apply **Deployments only after** images exist (or expect short `ImagePullBackOff` until builds finish).

---

## OpenTelemetry in Code

**W3C trace context:** Implementations MUST keep **W3C Trace Context** (**`traceparent`**) on the **UI → API** HTTP request. This repository uses Spring Boot + Micrometer OTel bridge defaults; do not replace **`RestClient`** with a raw client that omits context injection without also wiring **Observations** / propagators.

### 1. Frontend UI tracing (server-side)

- **Inbound HTTP:** Spring MVC / Tomcat creates a **server span** for each `/action/...` request; **`service.name`** = **`frontend-ui`** (`spring.application.name`).
- **Outbound HTTP:** **`RestClient`** to **`backend-app`** MUST run with the **active trace** so Micrometer injects **`traceparent`** (and optional **`tracestate`**) on the outbound call. **Requirement:** use the **instrumented** **`RestClient`** bean pattern (see `ClientConfig` / `UiController`); avoid ad-hoc **`HttpURLConnection`** or third-party clients that ignore context.
- **Browser:** This UI is **not** a SPA making direct traced XHR/fetch to the API. The **browser** only does a **full-page GET** to **`frontend-ui`**; the **trace starts in the frontend service** when that request hits the pod. **Browser-to-backend direct** calls would require **browser instrumentation** (e.g. OpenTelemetry JS) **and** CORS/header rules to propagate **`traceparent`** — **out of scope** for the default demo by design.

### 2. Backend app tracing

- **Inbound HTTP:** MUST **extract** W3C context from headers and create the **server span** as a **child** of the frontend client span (Spring Boot server instrumentation does this when headers are present).
- **Internal spans:** **Controller**, **service**, and **repository** layers MUST create spans **under** that server span (same trace id).
- **Database:** Manual **CLIENT** spans for SQL (**`SELECT …`**) MUST use the **current** context so **Postgres-related spans** stay in the **same trace** as the HTTP request.

### 3. Database spans in Jaeger

- Implemented as **OpenTelemetry client spans** (see **Database Span Visibility in Jaeger**). Hibernate may add additional internal spans depending on version; the manual spans are the **contract** for the lab.

### 4. Trace propagation (recap)

- **W3C Trace Context** on **frontend → backend** via Spring’s default propagators.
- **Single trace** chain: **frontend-ui** server → **frontend-ui** client → **backend-app** server → service → **DB client** (all **one trace id**).

### 5. Service naming

- **`frontend-ui`** — `spring.application.name` in `frontend-ui`.
- **`backend-app`** — `spring.application.name` in `backend-app`.
- **Postgres-related spans** — same **trace id** as the backend HTTP span; **`service.name`** remains **`backend-app`** on those spans unless you add peer/resource overrides (see **Optional: Force Frontend and Backend to Appear as the Same Service** only if you intentionally unify names).

**OTLP:** `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://otel-collector.observability-demo.svc.cluster.local:4318/v1/traces` on both Deployments.

---

## Jaeger UI Enablement

- Configured on **`TempoMonolithic`** in `openshift/10-tempo.yaml` (`jaegerui.enabled`, `route.enabled`).
- **Jaeger UI does not store traces** here — it **queries Tempo**.
- Access: **Route** in `observability-demo` (plus OAuth). Fallback: `oc port-forward` to the Jaeger UI / query **Service** if routes are restricted.

---

## Tracing Testing

**Goal:** Prove **one trace ID** for **one** UI action, with spans from **`frontend-ui`**, **`backend-app`**, and the **SQL client** span in the **same** Jaeger trace.

1. **Trigger exactly one UI action** — e.g. open **`frontend-ui`** Route (`oc get route frontend-ui -n observability-demo`) and click **Get Order** once (wait for the page to load). Each separate click creates a **new** trace; isolate **one** action for validation.
2. **Open Jaeger UI** (Tempo-backed route in **`observability-demo`**).
3. **Find that trace** — search by time window and/or service **`frontend-ui`** or **`backend-app`**.
4. **Open the trace detail** and confirm the waterfall includes spans from **both** services:
   - At least one span with **service** **`frontend-ui`** (inbound `/action/...` and outbound client to API).
   - At least one span with **service** **`backend-app`** (inbound `/api/...` and internal work).
5. **Confirm a single trace ID** — in the trace view, **every** span listed belongs to the **same** trace (Jaeger shows one **Trace ID** for the whole view; there must **not** be two unrelated traces for that single click).
6. **Confirm the PostgreSQL-related span** — under **`backend-app`**, find a **client** span such as **`SELECT orders`** (or equivalent) with **`db.system=postgresql`**; it MUST appear **inside this same trace**, not in a second trace.
7. **Interpretation:** **Multiple services** in **one** trace is **correct** distributed tracing behavior — **not** an error.

**Additional checks:** **Collector:** `oc logs -n observability-demo -l app.kubernetes.io/name=otel-collector --tail=80`. **If no traces:** **Troubleshooting** + OTLP env on both Deployments, Collector + Tempo pods, Postgres **Ready**. **Load (optional):** repeat clicks or `curl` the **frontend** Route (note: each request = its own trace).

**Jaeger search tip:** Searching or filtering by **one** service only **limits which traces appear in the list**; opening a trace still shows **all** spans (all services) for that trace ID. If you only see one service in the **trace detail**, propagation is likely broken — see **Troubleshooting — distributed trace correlation**.

---

## UI Click Trace Testing

1. Browse to **`https://<frontend-ui-route-host>/`**.
2. Click **Get Order** **once** → expect the result page for **`ORD-001`**.
3. In Jaeger, open **one** trace for that moment in time.
4. Verify **Trace ID** is shared: copy the trace id from the UI if available; confirm **all** listed spans use that id (no second trace for the same click).
5. Verify **service** **`frontend-ui`**: root / server span for **`/action/order/ORD-001`** (names may vary slightly) and **client** span toward **`/api/orders/ORD-001`**.
6. Verify **service** **`backend-app`**: **server** span for **`GET /api/orders/ORD-001`**, then **service** spans (**`OrderService.getOrder`** etc.), then **`SELECT orders`** (or equivalent) with **`db.system=postgresql`** — **all in this one trace**.
7. Repeat for **Check Inventory** (`SKU-100`) and **Calculate Price** — each click is a **new** trace; repeat steps 3–6 per action.

---

## Jaeger Waterfall Interpretation

- **Top to bottom:** Time flows downward; **width** ≈ duration.
- **Expected shape (Get Order):**  
  **frontend-ui** server (wide) contains **frontend-ui** client (narrower) → **backend-app** server → **OrderService.getOrder** → **`SELECT orders`** (often short but visible).
- **Latency:** Sum of **network + JVM + JDBC + Postgres**; DB span width is **query + round-trip**; missing widening in DB layer may mean the query is fast or the span is missing (see **Database Span Visibility**).
- **Missing DB span:** Usually **instrumentation** not reaching JPA (here mitigated by **manual** spans); if **only** auto-instrumentation were used, missing JDBC spans would point to disabled observations or unsupported stack.

---

## Verification Steps

| Goal | Command / action |
|------|------------------|
| Operators | `oc get csv -n openshift-tempo-operator` ; `oc get csv -n openshift-opentelemetry-operator` |
| Tempo / Collector | `oc get tempomonolithic,pods,opentelemetrycollector -n observability-demo` |
| Postgres | `oc get pods -l app.kubernetes.io/name=postgres -n observability-demo` ; `oc logs deploy/postgres -n observability-demo --tail=20` |
| Backend | `oc get pods -l app.kubernetes.io/name=backend-app -n observability-demo` ; `curl -sS http://$(oc get svc backend-app -o jsonpath='{.spec.clusterIP}' -n observability-demo):8080/api/orders/ORD-001` from a debug pod or port-forward |
| Frontend route | `oc get route frontend-ui -n observability-demo` |
| UI smoke | Browser: click all three actions |
| Jaeger | **Tracing Testing** + **UI Click Trace Testing** |
| Different `service.name` | Filter by **`frontend-ui`** vs **`backend-app`** in Jaeger service list |

---

## Expected Trace Flow

**One user click (e.g. Get Order)** — **one trace ID** end to end:

1. **frontend-ui** — Server span for `/action/order/ORD-001`.  
2. **frontend-ui** — Client span → **backend-app** `/api/orders/ORD-001`.  
3. **backend-app** — Server span for API.  
4. **backend-app** — `OrderService.getOrder` + **`SELECT orders`** (DB client).  

Minimum **three logical layers** visible: **UI pod**, **API pod**, **database client span** — all under the **same Jaeger trace** with **two services** (**`frontend-ui`**, **`backend-app`**). See **Same Trace ID Across Frontend and Backend**.

---

## Troubleshooting

| Issue | Notes |
|-------|--------|
| Tempo apply **warnings** (multitenancy / `extraConfig`) | See earlier doc revision / Red Hat guidance; lab uses single-tenant **TempoMonolithic** + Jaeger OAuth on the UI Route. |
| Postgres **ReplicaFailure** / SCC (`fsGroup` not allowed) | Do **not** set a fixed **`fsGroup`** outside the namespace range (e.g. `70` is rejected by **restricted-v2**). The manifest omits **`fsGroup`** so the platform assigns a valid group for **`emptyDir`**. |
| Postgres **CrashLoop** on restricted SCC | Try adjusting **image** or **securityContext** per cluster policy; ensure **`emptyDir`** and `PGDATA` subpath as in `40-postgres.yaml`. |
| **ImagePullBackOff** for `postgres:16-alpine` | Use an internal mirror or Red Hat image; update `40-postgres.yaml`. |
| Backend **unready** | Postgres not ready, wrong password, or DB not initialized — check `oc logs deploy/backend-app`. |
| **No DB spans** | Confirm backend image includes **manual** spans in services; check Jaeger for collapsed child spans. |
| **`debug` exporter** errors on Collector | Switch **`debug`** → **`logging`** in `11-otel-collector.yaml` pipeline. |

### Troubleshooting — distributed trace correlation

| Issue | Notes |
|-------|--------|
| **Two traces** for one UI click (frontend one id, backend another) | **`traceparent`** not received by **`backend-app`**: check **`RestClient`** is the instrumented bean; compare with **Same Trace ID Across Frontend and Backend**. Confirm no **manual HTTP** bypassing Micrometer. |
| **Headers not forwarded** | **Edge reverse proxy** / **API gateway** in front of UI or API must **forward** `traceparent` (and `tracestate` if used). OpenShift **Route** to the UI usually does not strip them; custom **Ingress** annotations or **corporate proxies** might. |
| **`@Async` / reactive** breaks propagation | Context is **thread-local** / **Reactor**-scoped; async work must use **supported** context propagation (e.g. **`@Observed`**, **`ContextSnapshot`**, Reactor operators). This demo uses **sync** `RestClient` only. |
| **Manual HTTP client** ignores propagation | **`HttpClient`**, raw **`RestTemplate`** without observation, or **Feign** without tracing deps may **omit** headers. Prefer **`RestClient`** + Boot observability or explicitly inject the **W3C propagator**. |
| **Service names correct but traces split** | Not a naming issue — **exporter** or **sampling** edge cases (rare with probability `1.0`), or **two different requests** (double fetch, prefetch). Use **one** deliberate click and match **timestamps**. |
| **Jaeger search by one service “hides” the other** | The **search list** filters **traces that contain** that service; opening a trace shows **all** spans. If the opened trace **only** shows **`frontend-ui`**, the backend never joined — see **headers not forwarded**. If the trace shows **both** services, behavior is **correct**. |
| **Spans exist but DB span in “another” trace** | Usually **two separate HTTP requests** or backend **creating a new root** for DB work — verify manual spans use **current context** (this repo wraps JPA calls in the request thread). |

---

## Cleanup

```bash
oc delete -f openshift/23-ui-route.yaml --ignore-not-found
oc delete -f openshift/22-ui-service.yaml --ignore-not-found
oc delete -f openshift/21-ui-deployment.yaml --ignore-not-found
oc delete -f openshift/32-backend-service.yaml --ignore-not-found
oc delete -f openshift/31-backend-deployment.yaml --ignore-not-found
oc delete -f openshift/30-backend-buildconfig.yaml --ignore-not-found
oc delete -f openshift/20-ui-buildconfig.yaml --ignore-not-found
oc delete -f openshift/40-postgres.yaml --ignore-not-found
oc delete -f openshift/11-otel-collector.yaml --ignore-not-found
oc delete -f openshift/10-tempo.yaml --ignore-not-found
oc delete project observability-demo
```

---

## Apply All

```bash
oc apply -f openshift/00-namespace.yaml
oc apply -f openshift/01-operatorgroup.yaml
oc apply -f openshift/02-subscriptions.yaml
# wait for CSVs Succeeded
oc apply -f openshift/10-tempo.yaml
oc apply -f openshift/11-otel-collector.yaml
oc apply -f openshift/40-postgres.yaml
oc rollout status deployment/postgres -n observability-demo --timeout=300s
oc apply -f openshift/30-backend-buildconfig.yaml
oc apply -f openshift/20-ui-buildconfig.yaml
```

Then **build images** (see below), then:

```bash
oc apply -f openshift/32-backend-service.yaml
oc apply -f openshift/31-backend-deployment.yaml
oc apply -f openshift/22-ui-service.yaml
oc apply -f openshift/23-ui-route.yaml
oc apply -f openshift/21-ui-deployment.yaml
```

---

## Build and Deploy App

```bash
oc start-build backend-app -n observability-demo --follow
oc start-build frontend-ui -n observability-demo --follow
oc rollout status deployment/backend-app -n observability-demo --timeout=300s
oc rollout status deployment/frontend-ui -n observability-demo --timeout=300s
```

---

## Tekton CI/CD (optional)

Install **OpenShift Pipelines**, then apply **`tekton/`** and follow **`tekton/README.md`**. Full architecture, parameters, validation, and troubleshooting are in **`OCP-4.18-Observability-Demo-Deployment.md`** under **Tekton CI/CD (OpenShift Pipelines)**.

---

## Web Console Install Path

**Tempo Operator:** **Administrator** → **Operators** → **OperatorHub** → search **Tempo** → **Install** → **`openshift-tempo-operator`**, **stable**, **Automatic**.

**Red Hat build of OpenTelemetry:** **OperatorHub** → **Red Hat build of OpenTelemetry** → **`openshift-opentelemetry-operator`**, **stable**, **Automatic**.

Then apply **`openshift/10-tempo.yaml`**, **`11-otel-collector.yaml`**, and the rest via CLI as above.

---

## Tracing Test Quickstart

```bash
HOST=$(oc get route frontend-ui -n observability-demo -o jsonpath='{.spec.host}')
open "https://${HOST}/"
# Click "Get Order", then open Jaeger UI route → service frontend-ui / backend-app → Find Traces
```

---

## OpenTelemetry in Code (recap)

- **frontend-ui** + **backend-app**: Micrometer **OTLP HTTP** → **`otel-collector.observability-demo.svc.cluster.local:4318`**.  
- **Propagation:** **W3C** on **RestClient** from UI to API.  
- **DB:** **Manual** `SpanKind.CLIENT` spans with **`db.*`** attributes on **`backend-app`**.  
- **Jaeger:** Queries **Tempo**; filter **`frontend-ui`** vs **`backend-app`** for layer proof.
