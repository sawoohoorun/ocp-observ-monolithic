# Smoke test — 3-layer observability demo

See **`OCP-4.18-Observability-Demo-Deployment.md`** (or **`README.md`**) section **Tracing Testing** for Jaeger UI validation.

Quick checks:

```bash
oc get pods -n dev
oc rollout status deployment/postgres -n dev
oc rollout status deployment/backend-app -n dev
oc rollout status deployment/frontend-ui -n dev
oc get route frontend-ui -n dev -o jsonpath='{.spec.host}{"\n"}'
```

Builds:

```bash
oc get builds -n dev
```
