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
| **1 — Frontend UI** | `frontend-ui` | Serves HTML with **Get Order / Check Inventory / Calculate Price** actions. Each action performs a **server-side** `RestClient` call to `backend-app` so **W3C `traceparent`** propagates on the outbound HTTP request. |
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

The previous single **`spring-monolith`** tree was **removed** in favor of this split.

---

## Repository Structure Summary

```text
├── backend-app/           # Maven, Spring Boot API + JPA
├── frontend-ui/           # Maven, Spring Boot + Thymeleaf
├── openshift/             # Namespaces, operators, Tempo, Collector, Postgres, builds, Deployments, Routes
├── tekton/                # Tekton Tasks + Pipeline + sample PipelineRun (CI/CD)
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
| **Frontend** | New Thymeleaf app + **`RestClient`** | **Layer 1** server span + **HTTP client** span to backend with **W3C** propagation. |
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

tekton/
├── 00-serviceaccount.yaml
├── 01-rbac.yaml
├── 02-task-git-clone.yaml … 07-task-emit-summary.yaml
├── 10-pipeline.yaml
├── 11-pipelinerun.yaml
├── 20-task-smoketest.yaml
└── README.md
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
- **Tekton path:** OpenShift Pipelines operator installed; **`tkn` CLI** optional but convenient (`brew install tektoncd-cli` or Red Hat mirror).

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

### 1. Frontend UI tracing

- **Inbound:** Spring MVC / Tomcat creates a **server span** for each `/action/...` request (`frontend-ui`).
- **Outbound:** **`RestClient`** is instrumented by Spring Boot 3 + Micrometer tracing; **`traceparent`** (W3C) is sent to **`backend-app`**. No browser JS instrumentation is required because the **browser only loads HTML**; tracing starts at the **frontend pod** when the user follows a link.

### 2. Backend app tracing

- **Controller:** Auto **HTTP server** spans for `/api/...`.
- **Service:** Manual spans **`OrderService.getOrder`**, **`InventoryService.checkStock`**, **`PricingService.getPrice`**.
- **Repository / DB:** Manual **client** spans **`SELECT orders`**, **`SELECT inventory`**, **`SELECT pricing`** wrapping JPA calls, with **`db.system`**, **`db.statement`**, etc.

### 3. Database spans in Jaeger

- Implemented as **OpenTelemetry client spans** (see **Database Span Visibility in Jaeger**). Hibernate may add additional internal spans depending on version; the manual spans are the **contract** for the lab.

### 4. Trace propagation

- **W3C Trace Context** on the HTTP call **frontend → backend** via Spring’s propagators.
- **Single trace** should chain: frontend server → frontend client → backend server → service → DB client.

### 5. Service naming

- **`frontend-ui`** — `spring.application.name` in `frontend-ui`.
- **`backend-app`** — `spring.application.name` in `backend-app`.
- **Postgres-related spans** — same trace as backend; span attributes carry **`db.system=postgresql`** (resource/service still typically **`backend-app`** unless you add peer service attributes).

**OTLP:** `MANAGEMENT_OTLP_TRACING_ENDPOINT=http://otel-collector.observability-demo.svc.cluster.local:4318/v1/traces` on both Deployments.

---

## Jaeger UI Enablement

- Configured on **`TempoMonolithic`** in `openshift/10-tempo.yaml` (`jaegerui.enabled`, `route.enabled`).
- **Jaeger UI does not store traces** here — it **queries Tempo**.
- Access: **Route** in `observability-demo` (plus OAuth). Fallback: `oc port-forward` to the Jaeger UI / query **Service** if routes are restricted.

---

## Tracing Testing

End-to-end validation checklist:

1. **Open** the **`frontend-ui`** Route in a browser (`oc get route frontend-ui -n observability-demo`).
2. **Click** **Get Order** (or Inventory / Price) — each click starts a **new trace**.
3. **Open Jaeger UI** Route (same namespace); select service **`frontend-ui`** and/or **`backend-app`**.
4. **Find** a trace matching the click time; root is often the **frontend** HTTP span.
5. **Confirm spans:**
   - **Frontend:** HTTP server for `/action/...`; HTTP **client** to `/api/...`.
   - **Backend:** HTTP server for `/api/...`; **`OrderService.*`** / **`InventoryService.*`** / **`PricingService.*`**; **`SELECT ...`** **client** spans with **`db.system=postgresql`**.
6. **Collector sanity:** `oc logs -n observability-demo -l app.kubernetes.io/name=otel-collector --tail=80`.
7. **If no traces:** See **Troubleshooting**; verify OTLP env on both Deployments, Collector + Tempo pods, and that Postgres is **Ready** before heavy backend traffic.
8. **Load (optional):** repeat clicks or script `curl` against the **frontend** Route (session-less links).

---

## UI Click Trace Testing

1. Browse to **`https://<frontend-ui-route-host>/`**.
2. Click **Get Order** → expect JSON-like payload for **`ORD-001`** on the result page.
3. In Jaeger, find a trace where the **first span** is associated with **`frontend-ui`** and path **`/action/order/ORD-001`** (names may vary slightly by instrumentation).
4. Expand children until you see **`backend-app`** **HTTP** span for **`GET /api/orders/ORD-001`**.
5. Under that, confirm **service** + **`SELECT orders`** (or equivalent) span.
6. Repeat for **Check Inventory** (`SKU-100`) and **Calculate Price** (`SKU-100`).

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

**One user click (e.g. Get Order):**

1. **frontend-ui** — Server span for `/action/order/ORD-001`.  
2. **frontend-ui** — Client span → **backend-app** `/api/orders/ORD-001`.  
3. **backend-app** — Server span for API.  
4. **backend-app** — `OrderService.getOrder` + **`SELECT orders`** (DB client).  

Minimum **three logical layers** visible: **UI pod**, **API pod**, **database client span**.

---

## Troubleshooting

| Issue | Notes |
|-------|--------|
| Tempo apply **warnings** (multitenancy / `extraConfig`) | See earlier doc revision / Red Hat guidance; lab uses single-tenant **TempoMonolithic** + Jaeger OAuth on the UI Route. |
| Postgres **CrashLoop** on restricted SCC | Try adjusting **image** or **securityContext** per cluster policy; ensure **`emptyDir`** and `PGDATA` subpath as in `40-postgres.yaml`. |
| **ImagePullBackOff** for `postgres:16-alpine` | Use an internal mirror or Red Hat image; update `40-postgres.yaml`. |
| Backend **unready** | Postgres not ready, wrong password, or DB not initialized — check `oc logs deploy/backend-app`. |
| **No DB spans** | Confirm backend image includes **manual** spans in services; check Jaeger for collapsed child spans. |
| **`debug` exporter** errors on Collector | Switch **`debug`** → **`logging`** in `11-otel-collector.yaml` pipeline. |

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

## Tekton CI/CD (OpenShift Pipelines)

This section extends the guide with a **Tekton-orchestrated** path suitable for **OpenShift 4.18** and **OpenShift Pipelines**: the pipeline is the **CI/CD orchestrator**; **OpenShift-native** `BuildConfig` / `ImageStreamTag` / `Deployment` patterns stay aligned with the manual flow above.

**Scope:** **frontend-ui** and **backend-app** each get their **own build**, **image** (`ImageStreamTag`), and **deployment** update. **PostgreSQL** is **infrastructure**: the pipeline **does not build** it — it **applies** `openshift/40-postgres.yaml` from the clone (idempotent) and **waits** for the `postgres` `Deployment` before relying on the backend. The **observability stack** (Tempo operator operand, Red Hat build of OpenTelemetry Collector, Jaeger UI) is treated as **preinstalled** by `openshift/10`–`11` and earlier operator steps; the pipeline **does not** re-apply subscriptions or operands every run.

Manifests and tasks live under **`tekton/`**; see **`tekton/README.md`** for a concise file list and apply order. This document avoids pasting full Tekton YAML.

---

### Tekton CI/CD Architecture

**Build strategy (explicit choice — Option A):** Tekton **orchestrates OpenShift `BuildConfig` builds** via **`oc start-build … --wait`**. The cluster builder still clones from Git using each `BuildConfig`’s `spec.source` and writes to **`ImageStreamTag`** `…:1.0.0`, matching **`openshift/20-ui-buildconfig.yaml`** and **`openshift/30-backend-buildconfig.yaml`**. This stays **consistent** with the rest of the guide (OpenShift-native builds, internal registry, `image.openshift.io/triggers` on `Deployment`s) and avoids maintaining duplicate Docker/buildah logic inside Tekton for this lab.

**Why not Option B (buildah/S2I inside Tekton) here:** For this repo, Option A reuses the **same** `Dockerfile` + Git `contextDir` already defined in `BuildConfig`; you do not need a second build implementation to keep images identical. Option B is still valid for clusters that disable `BuildConfig`; mark that as an **optional enhancement** if you migrate.

**Deployment / image update pattern (one approach, both apps):** The pipeline **`oc apply`s** `Deployment` YAML from the cloned **`openshift/`** directory. That reapplies **`MANAGEMENT_OTLP_TRACING_ENDPOINT`**, **`DEMO_BACKEND_BASE_URL`**, **`DB_PASSWORD`** wiring, and **`image.openshift.io/triggers`** annotations. **`oc start-build`** updates **`ImageStreamTag`**; OpenShift **updates the pod template image** from the trigger; **`oc rollout status`** waits until the new revision is ready. **Same pattern** for **backend** and **frontend**.

**Pipeline inputs**

| Input | Pipeline parameter | Role |
|--------|-------------------|------|
| Git repository URL | `git-url` | Clone source for layout check and for `openshift/` apply |
| Git revision / branch / tag / commit | `git-revision` | Checked out in the workspace after clone |
| Target namespace | `namespace` | All `oc` and apply targets (default `observability-demo`) |
| Frontend app name | `frontend-app-name` | `BuildConfig` + `Deployment` + Service name (`frontend-ui`) |
| Backend app name | `backend-app-name` | `BuildConfig` + `Deployment` + Service name (`backend-app`) |
| Optional commit for binary builds | `start-build-commit` | If set, passed to **`oc start-build --commit`** for both builds |

**Pipeline execution stages (ordered)**

1. Clone source into workspace.  
2. Verify repo contains **`frontend-ui/`**, **`backend-app/`**, **`openshift/`** (and key YAML files).  
3. **Apply** Postgres manifest from clone → **wait** for `postgres` rollout.  
4. **Apply** app `BuildConfig` / `Service` / `Deployment` / `Route` manifests from clone (no operators).  
5. **Build backend** (`oc start-build backend-app --wait`).  
6. **Wait** for **backend** `Deployment` rollout.  
7. **Build frontend** (`oc start-build frontend-ui --wait`).  
8. **Wait** for **frontend** `Deployment` rollout.  
9. **Smoke test** (in-cluster HTTP to frontend `Service`).  
10. **`finally`:** print **Routes**, **ImageStreamTag** hints, and **Jaeger UI** verification reminders.

**Pipeline outputs**

| Output | Where |
|--------|--------|
| Successful build/deploy status | `PipelineRun` / `TaskRun` phase, `tkn pipelinerun describe` |
| Deployed routes | `finally` task logs (`oc get routes`) |
| Image references | `oc get istag` in summary; Build objects in namespace |
| Smoke test result | `frontend-smoke-test` task logs (HTTP 200); trace **shape** confirmed **manually** in Jaeger |

```mermaid
flowchart TD
  Dev[Developer push / manual start] --> PR[PipelineRun]
  PR --> Clone[Clone monorepo]
  Clone --> Verify[Verify frontend-ui backend-app openshift]
  Verify --> ApplyPG[Apply postgres YAML wait rollout]
  ApplyPG --> ApplyApp[Apply BC Deploy Route from Git]
  ApplyApp --> BB[oc start-build backend-app]
  BB --> WB[rollout backend-app]
  WB --> BF[oc start-build frontend-ui]
  BF --> WF[rollout frontend-ui]
  WF --> Smoke[HTTP smoke via Service]
  Smoke --> Sum[finally: routes + Jaeger hints]
```

---

### Tekton Resources Overview

| Resource | Name / pattern | Purpose |
|----------|----------------|---------|
| `ServiceAccount` | `observability-demo-pipeline` | Identity for all `TaskRun` pods |
| `Role` + `RoleBinding` | `observability-demo-pipeline` | Namespace permissions for builds, deployments, routes, imagestreams, secrets/configmaps needed for apply |
| `Task` | `git-clone-demo-repo`, `verify-demo-repo-layout`, `apply-openshift-app-manifests`, `oc-start-build-wait`, `oc-rollout-status`, `frontend-smoke-test`, `emit-delivery-summary` | Reusable steps |
| `Pipeline` | `observability-demo-delivery` | Ordered DAG + `finally` summary |
| `PipelineRun` | e.g. `observability-demo-delivery-xxxxx` | One execution; binds workspace and parameters |
| Workspace | `source` | Clone + `oc apply -f` paths under `$(workspaces.source.path)/openshift` |
| Secrets (optional) | Git credentials | Only if `git-url` is private — attach to `ServiceAccount` or use a `git-clone` pattern with secret volume |

**Bundled / catalog tasks:** This lab uses **custom Tasks** in `tekton/` (UBI + `git`, **`registry.redhat.io/openshift4/ose-cli`** for `oc`) so you are not tied to a specific **Tekton Hub** resolver version. Conceptually they replace bundles such as **`git-clone`** + **`openshift-client`** ClusterTasks — same ideas, fewer moving parts for a fresh cluster.

**SCC / elevation:** Tasks run as **normal pods** in **`observability-demo`** using the pipeline **`ServiceAccount`**. **No custom SCC** or cluster-admin is required for the sample RBAC. If your cluster enforces **restricted** defaults, the stock `ose-cli` and UBI images are typically compatible; if pull fails, mirror images and edit task `image:` fields.

---

### Pipeline Flow

**Dependency order:** **Postgres ready** → **backend image + rollout** → **frontend image + rollout** → **smoke** (frontend must not be validated before the API is ready). **Frontend** build waits until **backend** rollout completes so a failing API is caught before spending UI build time.

**Tekton pipeline flow (narrative):** A developer **pushes** to Git (or you **start** a `PipelineRun` manually). The pipeline **clones** the repository and **confirms** the **multi-app layout** (`frontend-ui/`, `backend-app/`, `openshift/`). It **applies** **PostgreSQL** and **application** YAML from that clone — **not** the operators — then runs **`oc start-build`** for **backend-app**, waits for **rollout**, then **frontend-ui**, waits again, runs an **in-cluster smoke** call to the **frontend Service**, and prints **Routes** and reminders to open **Jaeger UI** and confirm a **three-layer** trace.

---

### Pipeline Workspaces and Parameters

**Workspace `source`**

- **Purpose:** Holds the Git working tree so steps can run `test -d frontend-ui` and `oc apply -f …/openshift/40-postgres.yaml`.  
- **Binding:** Example `PipelineRun` uses **`emptyDir`** (no PVC). For larger clones, use a **`volumeClaimTemplate`** on the `PipelineRun` (see comment patterns in **`tekton/README.md`**).

**Parameters (summary)**

| Parameter | Default | Notes |
|-----------|---------|--------|
| `git-url` | (required at start) | HTTPS or SSH URL your builders can reach |
| `git-revision` | `main` | Branch, tag, or commit for `git checkout` after clone |
| `start-build-commit` | `""` | If non-empty, **`oc start-build --commit`** for both `BuildConfig`s |
| `namespace` | `observability-demo` | Target project |
| `backend-app-name` | `backend-app` | Must match `BuildConfig` / `Deployment` metadata names |
| `frontend-app-name` | `frontend-ui` | Same |

---

### Pipeline Tasks

| Task | Role |
|------|------|
| `git-clone-demo-repo` | Clone `git-url` / `git-revision` into workspace |
| `verify-demo-repo-layout` | Fail if expected directories / files are missing |
| `apply-openshift-app-manifests` | `oc apply` **`40-postgres`** → **rollout** `postgres`; then **`20–23`**, **`30–32`** app manifests (tracing env preserved from Git) |
| `oc-start-build-wait` | **Option A** — `oc start-build <bc> --wait --follow` (optional `--commit`) |
| `oc-rollout-status` | `oc rollout status deployment/<name>` |
| `frontend-smoke-test` | `curl` **readiness** and **`/action/order/ORD-001`** against **`http://frontend-ui.<ns>.svc.cluster.local:8080`**; optional **collector** log grep |
| `emit-delivery-summary` (`finally`) | **`oc get routes`**, **`istag`**, Jaeger instructions |

**`finally`:** The summary task runs even when a prior task fails, so you still get **routes** and hints for debugging.

---

### PipelineRun Examples

**1. Manifest-based (sample in repo)**

```bash
oc create -f tekton/11-pipelinerun.yaml
```

Edit **`tekton/11-pipelinerun.yaml`** first if your `git-url` / branch differ.

**2. CLI (`tkn pipeline start`)**

```bash
tkn pipeline start observability-demo-delivery -n observability-demo \
  --showlog \
  -w name=source,emptyDir="" \
  -p git-url=https://github.com/sawoohoorun/ocp-observ-monolithic.git \
  -p git-revision=main \
  -p namespace=observability-demo
```

**3. OpenShift web console:** **Pipelines** → **PipelineRuns** → **Create** / **Start** (select pipeline, bind workspace `source` to emptyDir or PVC, set parameters). See **Web console — Tekton** below.

**4. Git webhook (optional):** Not required for the lab; see **Optional Enhancements**.

---

### CI/CD Deployment Flow

**Repository layout (single clone, multiple components):** The pipeline treats the repo as a **monorepo**: **`frontend-ui/`** and **`backend-app/`** are **sibling** directories. **`openshift/20-ui-buildconfig.yaml`** sets **`contextDir: frontend-ui`**; **`openshift/30-backend-buildconfig.yaml`** sets **`contextDir: backend-app`**. **`oc start-build`** does **not** use the Tekton workspace as build context; the **cluster builder** checks out Git per **`BuildConfig`**. The **workspace** is still used to **`oc apply`** the **same** YAML you version in Git (so **OTLP env**, **triggers**, and **routes** stay in sync with the branch you cloned).

**PostgreSQL:** The pipeline **does not rebuild** an image for Postgres. It **`oc apply`s** **`openshift/40-postgres.yaml`** from the clone (idempotent) and **waits** for **`deployment/postgres`**. That matches **“deploy if not present / upgrade declaratively”** without a database **build** step.

**Observability stack:** **Do not** `oc apply` **`openshift/00`–`11`** from this pipeline each run. Install **Tempo** + **Red Hat build of OpenTelemetry** + operands **once** (or when upgrading the platform stack). The pipeline may **grep collector logs** during smoke tests; it does **not** replace operator lifecycle management.

---

### Observability-Aware CI/CD (tracing preserved)

1. **OTEL-related env / ConfigMaps:** **`apply-openshift-app-manifests`** reapplies **`openshift/21-ui-deployment.yaml`** and **`openshift/31-backend-deployment.yaml`**, which already set **`MANAGEMENT_OTLP_TRACING_ENDPOINT`** and sampling. **ConfigMap-only** OTLP config is not used in this demo; env vars are the source of truth. Re-applying those files after each clone keeps **pipeline-driven deploys** aligned with Git.  
2. **Frontend tracing config:** Preserved because the **Deployment** manifest in Git carries **`MANAGEMENT_OTLP_TRACING_ENDPOINT`**, **`MANAGEMENT_TRACING_SAMPLING_PROBABILITY`**, and **`DEMO_BACKEND_BASE_URL`**.  
3. **Backend tracing config:** Same pattern for OTLP env; **`DB_PASSWORD`** remains **`secretKeyRef`**.  
4. **Distinct service names:** **`spring.application.name`** stays **`frontend-ui`** vs **`backend-app`** in each app’s source; rebuilding images does not change that contract.  
5. **JDBC / PostgreSQL spans:** Implemented in **backend** code (manual **CLIENT** spans). Pipeline **does not** disable datasource observation; it only replaces images and reapplies YAML.  
6. **Smoke vs Jaeger:** The **Tekton smoke task** proves **HTTP** health through the **frontend Service**. **Validating a 3+ layer trace** (**frontend-ui**, **backend-app**, **SELECT …** with **`db.system=postgresql`**) is **manual in Jaeger UI** (Tempo-backed query) — querying Jaeger’s API from a Task is **optional** and heavier than needed for this demo.

---

### Tekton-Driven Smoke Testing (Markdown spec)

**Automated in pipeline (`frontend-smoke-test` Task):**

- Call **`GET /actuator/health/readiness`** on **`frontend-ui.<namespace>.svc.cluster.local:8080`** → expect **HTTP 200**.  
- Call **`GET /action/order/ORD-001`** on the same host → expect **HTTP 200** (Thymeleaf page rendered; server-side **RestClient** hits **backend** and DB).  
- Optionally **`oc logs`** for pods labeled **`app.kubernetes.io/name=otel-collector`** and **grep** for export / OTLP hints (best-effort; **not** a strict assertion).

**Manual (expected lab outcome):**

- Open **Jaeger UI** (route from **TempoMonolithic**).  
- Generate traffic (smoke task already did; or click **Get Order** in a browser).  
- Find a trace showing **at least three logical layers:** **frontend-ui** (server + client to API), **backend-app** (HTTP + service spans), and a **SQL / JDBC client** span (**`SELECT …`**, **`db.system=postgresql`**) under the backend.  

**Direct Jaeger API validation** from Tekton is **deliberately out of scope** for this demo; use **pipeline smoke for app health** and **Jaeger UI for trace visualization**.

---

### Pipeline Validation

| Check | How |
|-------|-----|
| Pipeline defined | `tkn pipeline ls -n observability-demo` or `oc get pipelines.tekton.dev -n observability-demo` |
| Run started | `oc get pipelineruns -n observability-demo` |
| Tasks succeeded | `tkn pipelinerun describe <name> -n observability-demo` ; `oc get taskruns -n observability-demo` |
| Builds succeeded | `oc get builds -n observability-demo` ; `oc logs build/<build-name> -n observability-demo` |
| Deployments rolled out | `oc rollout status deployment/backend-app` / `frontend-ui` ; `oc get pods -n observability-demo` |
| Routes reachable | `oc get route frontend-ui -n observability-demo` ; browser or `curl -k https://<host>/` |
| Smoke task | Logs show **OK: frontend readiness + Get Order returned HTTP 200** |
| Jaeger trace | **Tracing Testing** + **UI Click Trace Testing** sections in this guide |

**CLI quick reference**

```bash
oc apply -f tekton/00-serviceaccount.yaml
oc apply -f tekton/01-rbac.yaml
oc apply -f tekton/02-task-git-clone.yaml
oc apply -f tekton/03-task-verify-repo-layout.yaml
oc apply -f tekton/04-task-apply-openshift-apps.yaml
oc apply -f tekton/05-task-oc-start-build.yaml
oc apply -f tekton/06-task-oc-rollout-status.yaml
oc apply -f tekton/07-task-emit-summary.yaml
oc apply -f tekton/20-task-smoketest.yaml
oc apply -f tekton/10-pipeline.yaml

tkn pipeline ls -n observability-demo
tkn pipeline start observability-demo-delivery -n observability-demo --showlog \
  -w name=source,emptyDir="" \
  -p git-url=https://github.com/sawoohoorun/ocp-observ-monolithic.git \
  -p git-revision=main

oc get pipelineruns -n observability-demo
tkn pipelinerun logs -f <pipelinerun-name> -n observability-demo
oc get taskruns -n observability-demo
oc describe pipelinerun <pipelinerun-name> -n observability-demo
```

---

### Web Console — Tekton

1. **Administrator** or **Developer** perspective → **Pipelines** (OpenShift Pipelines installed).  
2. **Pipelines** page → confirm **`observability-demo-delivery`** exists in **`observability-demo`**.  
3. **Start** a run → set parameters (`git-url`, `git-revision`, …) and bind workspace **`source`** (**emptyDir** or PVC).  
4. **PipelineRuns** → open your run → inspect **TaskRuns** and **Logs** per step.  
5. After success: **Topology** or **Workloads** → **Deployments** **frontend-ui** / **backend-app**; **Networking** → **Routes** for the UI and Jaeger.

---

### Security and Permissions (pipeline ServiceAccount)

The **`observability-demo-pipeline`** `Role` grants **namespace-scoped** verbs to manage **`BuildConfig`/`Build`**, **`ImageStream*`**, **`Deployment`**, **`Service`**, **`Route`**, and read/update **`ConfigMap`/`Secret`** as needed for **`oc apply`** of the demo manifests. It does **not** grant cluster-admin or operator CRDs in other namespaces.

**If** you only run builds and rollouts (no apply), you could narrow rules — for this demo, **apply-from-Git** needs create/update on those types.

**No extra SCC** is required beyond what **restricted** namespaces allow for **UBI** and **ose-cli** task pods, assuming cluster image pull policy allows the referenced registries.

---

### Troubleshooting Tekton Pipeline

| Symptom | Likely cause / action |
|---------|------------------------|
| **Git clone** fails | Wrong URL; private repo without **Secret** / **ServiceAccount** linkage; cluster egress blocked |
| **Workspace empty / permission denied** | Workspace not bound on `PipelineRun`; `emptyDir` vs PVC; check `tkn pipelinerun describe` |
| **Frontend or backend build fails** | See **`oc logs build/…`**; Maven/Dockerfile error; Git ref missing for **`--commit`** |
| **Image updated but Deployment unchanged** | **`image.openshift.io/triggers`** annotation missing or wrong tag; compare **`istag`** to **`Deployment` pod spec** |
| **Rollout timeout** | **CrashLoop** (DB password, OTLP, memory); **`oc logs deployment/…`**; increase task timeout param |
| **Route not reachable** | **Route** not applied; DNS; TLS; pod not ready |
| **Smoke test fails** | Backend not ready; **Service** name mismatch; network policy blocking **ClusterIP** traffic from **Tekton** pods |
| **App works, no traces in Jaeger** | **Collector** / **Tempo** pods down; wrong **`MANAGEMENT_OTLP_TRACING_ENDPOINT`**; see **Troubleshooting** (non-Tekton table) |
| **Postgres spans missing** | Backend image not the instrumented one; **manual spans** disabled; DB not reachable — see **Database Span Visibility** |

---

### Optional Enhancements

- **Git webhook / TriggerTemplate / EventListener** to start `PipelineRun` on push.  
- **Separate dev / stage namespaces** with promoted `ImageStreamTag` or `kustomize` overlays.  
- **Approval task** (Slack/Manual gate) before **`apply-openshift-apps`** or before **`build-frontend`**.  
- **Extra CI stages:** **Maven test**, **lint**, **container scan** (`trivy`/`clair` Task) before deploy.  
- **Rollback:** keep previous **`ImageStreamTag`** revision and `oc rollback deployment/…` runbook.  
- **Path-scoped triggers:** only run **frontend** or **backend** build tasks when corresponding paths change (CEP / in-cluster interceptor or external CI).  
- **Option B builds:** replace **`oc start-build`** Tasks with **buildah** or **s2i-java** Tasks pushing to the internal registry — document a **single** strategy if you switch.

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
