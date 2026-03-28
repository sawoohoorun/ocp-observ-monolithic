# Smoke test — Observability demo (OCP 4.18)

For **Jaeger UI** steps (search service, traces, span hierarchy), use the **`Tracing Testing`** section in `OCP-4.18-Observability-Demo-Deployment.md` / `README.md`.

## 0. Build finished

```bash
oc get builds -n observability-demo
oc logs -n observability-demo build/demo-monolith-1 --tail=50
```

(Replace `demo-monolith-1` with your latest build name from `oc get builds`.)  
Expected: build phase **Complete**.

```bash
oc describe is demo-monolith -n observability-demo
```

Expected: tag **1.0.0** points at an image (digest populated).

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
