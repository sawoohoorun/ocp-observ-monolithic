# Smoke test — 3-layer observability demo

See **`OCP-4.18-Observability-Demo-Deployment.md`** (or **`README.md`**) section **Tracing Testing** for Jaeger UI validation.

Quick checks:

```bash
oc get pods -n observability-single-span
oc rollout status deployment/postgres -n observability-single-span
oc rollout status deployment/backend-app -n observability-single-span
oc rollout status deployment/frontend-ui -n observability-single-span
oc get route frontend-ui -n observability-single-span -o jsonpath='{.spec.host}{"\n"}'
```

Builds:

```bash
oc get builds -n observability-single-span
```
