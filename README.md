# OpenShift 4.18 Observability Demo — 3-Layer App, Tempo, Collector & Jaeger UI

> **Git branch `single-span`:** UI and API export traces as **one Jaeger service** (`order-demo-app`) with a unified span hierarchy — see **`docs/single-span-tracing.md`**.

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

- **Two OpenTelemetry producer Deployments** (**`frontend-ui`**, **`backend-app`**) → same **Collector** → **Tempo** → **Jaeger UI**; on **`single-span`**, both export **`service.name` `order-demo-app`**.
- **PostgreSQL** stores demo rows only; traces are **not** in Postgres.

---

## Upgraded 3-Layer Application Architecture

| Layer | Workload | Responsibility |
|-------|----------|----------------|
| **1 — Frontend UI** | `frontend-ui` | Serves HTML with **Get Order / Check Inventory / Calculate Price** actions. Each action performs a **server-side** `RestClient` call to `backend-app` so **W3C `traceparent`** propagates and **one trace ID** spans UI + API + DB. On **`single-span`**, OTLP **`service.name`** is **`order-demo-app`** on both tiers (see **`docs/single-span-tracing.md`**). |
| **2 — Backend monolith** | `backend-app` | REST `/api/...`, **controller** spans (auto), **service** spans (manual + business logic), **database client** spans (manual `SpanKind.CLIENT` around JPA reads + `db.*` attributes). |
| **3 — Data** | `postgres` | Single non-HA **Deployment**, **`emptyDir`** data volume, **init SQL** via ConfigMap → `docker-entrypoint-initdb.d`. |

---

## Frontend UI Design

- **Stack:** Spring Boot **3.4**, **Thymeleaf** (server-side rendering — no browser JS tracing required for the demo).
- **Service name (this branch):** `spring.application.name: order-demo-app` → OTLP **`service.name`** **`order-demo-app`**.
- **Outbound calls:** `RestClient` to `demo.backend-base-url` (overridden in-cluster by **`DEMO_BACKEND_BASE_URL`** → `http://backend-app.observability-demo.svc.cluster.local:8080`).
- **User actions:** Links such as `/action/order/ORD-001`, `/action/inventory/SKU-100`, `/action/pricing/SKU-100` each trigger one backend round-trip and **one distributed trace** spanning UI + API + DB.

---

## Backend Monolith Design

- **Stack:** Spring Web, Actuator, **Data JPA**, **PostgreSQL** driver, **Micrometer tracing bridge → OTLP**.
- **Service name (this branch):** **`order-demo-app`** (same resource as UI for Jaeger).
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

1. **Browser → UI Deployment:** vanilla HTTP GET; Tomcat/Spring creates the **root SERVER** span (named e.g. **`app: user click get order`**) with **`service.name` `order-demo-app`**.
2. **UI → API:** **`RestClient`** injects **`traceparent`**; backend Tomcat creates a **child SERVER** span for `/api/...` (same trace id, same **`order-demo-app`** process in Jaeger).
3. **Backend internal:** **INTERNAL** business spans and **CLIENT** **`postgres: SELECT …`** attach under the API HTTP span.
4. **Export:** Both apps send OTLP **HTTP** to **`http://otel-collector.observability-demo.svc.cluster.local:4318/v1/traces`**.

**Correlation contract:** One user action MUST yield **one trace ID**. On branch **`single-span`**, both workloads export **`service.name` = `order-demo-app`** so Jaeger shows **one logical application** in the service list. Full span naming and verification: **`docs/single-span-tracing.md`**.

---

## Same trace ID and single Jaeger service (`order-demo-app`)

**Tracing:** **`frontend-ui`** and **`backend-app`** `application.yaml` set **`spring.application.name: order-demo-app`** and **`management.opentelemetry.resource-attributes.service.name: order-demo-app`**. The browser hits the **UI Deployment**; **`RestClient`** calls the **backend Service**; W3C context continues the trace.

**Inbound UI (SERVER):** `UiServerObservationConvention` sets contextual names **`app: user click get order`**, **`app: user click check inventory`**, **`app: user click calculate price`** (per route).

**Manual spans:** **`frontend: prepare request`** (**INTERNAL**) wraps the backend HTTP call. Backend services use **INTERNAL** **`backend: load order`** / **`backend: calculate inventory`** / **`backend: calculate price`** and **CLIENT** **`postgres: SELECT …`** with **`db.*`** attributes.

**Implementation requirement:** Preserve **W3C Trace Context** on the **frontend → backend** hop (**`traceparent`** on **`RestClient`**).

### Headers: `traceparent`, `tracestate`, and `baggage`

| Mechanism | Role in this demo |
|-----------|-------------------|
| **`traceparent`** (W3C Trace Context) | **Required** on the wire. Injected on **`RestClient`**, extracted on the API server. |
| **`tracestate`** | **Optional**; absence alone does not mean broken propagation. |
| **W3C Baggage** | **Not used** in the default configuration. |

### Jaeger

Filter **Service** **`order-demo-app`**; open a trace and confirm the hierarchy in **`docs/single-span-tracing.md`**.

### Troubleshooting split traces

See **Troubleshooting — distributed trace correlation** in the main table: missing **`traceparent`**, async without propagation, wrong client, or sampling.

---

## Database Span Visibility in Jaeger

- **Approach:** **Manual OpenTelemetry spans** (`SpanKind.CLIENT`) named **`postgres: SELECT orders`**, **`postgres: SELECT inventory`**, **`postgres: SELECT pricing`**, with attributes **`db.system=postgresql`**, **`db.statement`**, **`db.sql.table`**.
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
| **Layout** | Replaced single `spring-monolith` with **`frontend-ui/`** + **`backend-app/`** | Separate deployments and routes; **`single-span`** uses one OTLP **`service.name`**. |
| **Manifests** | Moved to **`openshift/`** with numbered files (`20`–`40`) | Matches OpenShift-focused layout you requested. |
| **Backend** | JPA + Postgres + **INTERNAL** / **CLIENT** span names per **`docs/single-span-tracing.md`** | One logical app waterfall in Jaeger. |
| **Frontend** | Thymeleaf + **`RestClient`** + **`UiServerObservationConvention`** | **W3C** propagation + **SERVER** **`app: user click …`** roots. |
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

- **Inbound HTTP:** Spring MVC / Tomcat creates a **SERVER** span per `/action/...`; contextual name from **`UiServerObservationConvention`** (**`app: user click …`**). **`service.name`** = **`order-demo-app`**.
- **Manual UI spans:** **`UiController`** adds **INTERNAL** **`frontend: prepare request`** around **`RestClient`**.
- **Outbound HTTP:** **`RestClient`** to **`backend-app`** MUST run with the **active trace** so Micrometer injects **`traceparent`** (and optional **`tracestate`**) on the outbound call. **Requirement:** use the **instrumented** **`RestClient`** bean pattern (see `ClientConfig` / `UiController`); avoid ad-hoc **`HttpURLConnection`** or third-party clients that ignore context.
- **Browser:** This UI is **not** a SPA making direct traced XHR/fetch to the API. The **browser** only does a **full-page GET** to **`frontend-ui`**; the **trace starts in the frontend service** when that request hits the pod. **Browser-to-backend direct** calls would require **browser instrumentation** (e.g. OpenTelemetry JS) **and** CORS/header rules to propagate **`traceparent`** — **out of scope** for the default demo by design.

### 2. Backend app tracing

- **Inbound HTTP:** MUST **extract** W3C context from headers and create the **server span** as a **child** of the frontend client span (Spring Boot server instrumentation does this when headers are present).
- **Internal spans:** **INTERNAL** **`backend: load order`**, **`backend: calculate inventory`**, **`backend: calculate price`** under the API HTTP span (same trace id).
- **Database:** Manual **CLIENT** spans for SQL (**`SELECT …`**) MUST use the **current** context so **Postgres-related spans** stay in the **same trace** as the HTTP request.

### 3. Database spans in Jaeger

- Implemented as **OpenTelemetry client spans** (see **Database Span Visibility in Jaeger**). Hibernate may add additional internal spans depending on version; the manual spans are the **contract** for the lab.

### 4. Trace propagation (recap)

- **W3C Trace Context** on **frontend → backend** via Spring’s default propagators.
- **Single trace** chain: UI **SERVER** → **INTERNAL** `frontend: prepare request` → **CLIENT** HTTP → API **SERVER** → **INTERNAL** `backend: …` → **CLIENT** `postgres: SELECT …` (all **one trace id**).

### 5. Service naming

- **Both pods:** **`order-demo-app`** via `spring.application.name` and **`management.opentelemetry.resource-attributes`**.
- **Postgres-related spans** — same **trace id**; **`service.name`** **`order-demo-app`** (exported from the backend process).

**OTLP:** `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://otel-collector.observability-demo.svc.cluster.local:4318/v1/traces` on both Deployments.

---

## Jaeger UI Enablement

- Configured on **`TempoMonolithic`** in `openshift/10-tempo.yaml` (`jaegerui.enabled`, `route.enabled`).
- **Jaeger UI does not store traces** here — it **queries Tempo**.
- Access: **Route** in `observability-demo` (plus OAuth). Fallback: `oc port-forward` to the Jaeger UI / query **Service** if routes are restricted.

---

## Tracing Testing

**Goal:** Prove **one trace ID** for **one** UI action, with **Service** **`order-demo-app`** and the span hierarchy in **`docs/single-span-tracing.md`**.

**Look for:** **`app: user click …`**, **`frontend: prepare request`**, **`backend: load order`** (or inventory/pricing equivalents), **`postgres: SELECT …`**.

1. **Trigger exactly one UI action** — e.g. open the **`frontend-ui`** Route and click **Get Order** once.
2. **Open Jaeger UI** (Tempo-backed route in **`observability-demo`**).
3. **Service** = **`order-demo-app`** → **Find Traces** → open **one** trace.
4. Confirm **one trace ID** for all spans and the parent/child shape in **`docs/single-span-tracing.md`**.
5. Confirm **CLIENT** **`postgres: SELECT …`** with **`db.system=postgresql`** in the **same** trace.

**Additional checks:** **Collector:** `oc logs -n observability-demo -l app.kubernetes.io/name=otel-collector --tail=80`. **If no traces:** **Troubleshooting** + OTLP env on both Deployments, Collector + Tempo pods, Postgres **Ready**. **Load (optional):** repeat clicks or `curl` the **frontend** Route (note: each request = its own trace).

**Jaeger search tip:** With a single **`service.name`**, the service list shows **`order-demo-app`** once; the trace detail still shows the full span tree.

---

## UI Click Trace Testing

1. Browse to **`https://<frontend-ui-route-host>/`**.
2. Click **Get Order** **once** → expect the result page for **`ORD-001`**.
3. In Jaeger, open **one** trace for that moment in time.
4. Verify **Trace ID** is shared: copy the trace id from the UI if available; confirm **all** listed spans use that id (no second trace for the same click).
5. Verify **process** tags show **`order-demo-app`** for all spans; root **SERVER** **`app: user click get order`**; **INTERNAL** **`frontend: prepare request`**; **INTERNAL** **`backend: load order`**; **CLIENT** **`postgres: SELECT orders`** — **same trace ID**.
7. Repeat for **Check Inventory** (`SKU-100`) and **Calculate Price** — each click is a **new** trace; repeat steps 3–6 per action.

---

## Jaeger Waterfall Interpretation

- **Top to bottom:** Time flows downward; **width** ≈ duration.
- **Expected shape (Get Order):**  
  **SERVER** **`app: user click get order`** → **INTERNAL** **`frontend: prepare request`** → **CLIENT** HTTP → **SERVER** **`GET /api/orders/...`** → **INTERNAL** **`backend: load order`** → **CLIENT** **`postgres: SELECT orders`**.
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
| Jaeger service | Filter **`order-demo-app`** |

---

## Expected Trace Flow

**One user click (e.g. Get Order)** — **one trace ID** end to end:

1. **SERVER** **`app: user click get order`** (UI pod).  
2. **INTERNAL** **`frontend: prepare request`** + **CLIENT** HTTP to API.  
3. **SERVER** **`GET /api/orders/ORD-001`** (API pod).  
4. **INTERNAL** **`backend: load order`** + **CLIENT** **`postgres: SELECT orders`**.  

All spans use **`service.name` `order-demo-app`** in Jaeger. See **`docs/single-span-tracing.md`**.

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
| **Jaeger search** | Use service **`order-demo-app`**; opening a trace shows **all** spans for that trace id. |
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
# Click "Get Order", then open Jaeger UI route → service order-demo-app → Find Traces
```

---

## OpenTelemetry in Code (recap)

- **frontend-ui** + **backend-app**: Micrometer **OTLP HTTP** → **`otel-collector.observability-demo.svc.cluster.local:4318`**.  
- **Propagation:** **W3C** on **RestClient** from UI to API.  
- **DB:** **Manual** `SpanKind.CLIENT` spans with **`db.*`** attributes on **`backend-app`**.  
- **Jaeger:** Queries **Tempo**; filter **`order-demo-app`** (this branch).
