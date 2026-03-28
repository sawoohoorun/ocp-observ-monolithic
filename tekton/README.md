# Tekton delivery pipeline (OpenShift Pipelines)

This folder contains a **demo-friendly** Tekton `Pipeline` that automates clone → layout check → **apply app YAML from Git** → **OpenShift `BuildConfig` builds** (backend then frontend) → rollout waits → in-cluster smoke test → summary.

**Prerequisite:** [OpenShift Pipelines](https://docs.redhat.com/en/documentation/openshift_container_platform/4.18/html/cicd/pipelines) (Tekton) installed on the cluster. The pipeline assumes **operators, Tempo, and the OpenTelemetry Collector** are already applied (`openshift/00`–`11`); it does **not** reinstall them.

## File tree

| File | Purpose |
|------|---------|
| `00-serviceaccount.yaml` | `ServiceAccount` `observability-demo-pipeline` |
| `01-rbac.yaml` | Namespace `Role` + `RoleBinding` (builds, deploys, routes, imagestreams, etc.) |
| `02-task-git-clone.yaml` | Clone the monorepo into the shared workspace |
| `03-task-verify-repo-layout.yaml` | Require `frontend-ui/`, `backend-app/`, key `openshift/*.yaml` |
| `04-task-apply-openshift-apps.yaml` | `oc apply` Postgres + app BC/Deployment/Service/Route from clone (no operators) |
| `05-task-oc-start-build.yaml` | `oc start-build --wait` for a `BuildConfig` |
| `06-task-oc-rollout-status.yaml` | `oc rollout status` for a `Deployment` |
| `07-task-emit-summary.yaml` | Print routes, `ImageStreamTag`s, Jaeger hints (`finally`) |
| `10-pipeline.yaml` | `Pipeline` `observability-demo-delivery` |
| `11-pipelinerun.yaml` | Example `PipelineRun` with `emptyDir` workspace |
| `20-task-smoketest.yaml` | In-cluster HTTP smoke + optional collector log grep |

## Apply order

From the repo root (after namespace and observability stack exist):

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
# Optional: oc apply -f tekton/11-pipelinerun.yaml
```

Or apply the directory (same order is not guaranteed by `kubectl`/`oc` — prefer explicit order or `kubectl apply -f tekton/` once; if you hit ordering issues, apply in the sequence above).

```bash
oc apply -f tekton/
```

## Start a run (`tkn`)

```bash
tkn pipeline ls -n observability-demo
tkn pipeline start observability-demo-delivery -n observability-demo \
  --showlog \
  -w name=source,emptyDir="" \
  -p git-url=https://github.com/sawoohoorun/ocp-observ-monolithic.git \
  -p git-revision=main \
  -p namespace=observability-demo
```

Follow logs for an existing run:

```bash
oc get pipelineruns -n observability-demo
tkn pipelinerun logs -f <pipelinerun-name> -n observability-demo
```

## Private Git

Create a `Secret` of type `kubernetes.io/basic-auth` or `kubernetes.io/ssh-auth`, link it to the `ServiceAccount` (`secrets` field), and use an SSH URL or HTTPS with credentials per your cluster’s Git documentation. The sample clone task uses a public HTTPS URL.

## Images used by tasks

Tasks pull **`registry.redhat.io/openshift4/ose-cli`**, **`docker.io/alpine/git`** (clone), **`docker.io/curlimages/curl`** (smoke HTTP), and **`registry.access.redhat.com/ubi9/ubi-minimal`** (verify). Ensure pulls are allowed, or mirror and edit the task YAML.

## Pod Security Admission (restricted)

Tasks set **`stepTemplate.securityContext`** (`capabilities.drop: ["ALL"]`, **`runAsNonRoot: true`**, **`seccompProfile: RuntimeDefault`**) and the **Pipeline** / sample **PipelineRun** set **`taskRunTemplate.podTemplate.securityContext`**. The clone step no longer runs **`dnf`** as root. If **Tekton entrypoint** init containers still violate **restricted** on your operator version, upgrade **OpenShift Pipelines** or configure **`TektonConfig` `default-pod-template`** — see **Troubleshooting Tekton Pipeline** in **`OCP-4.18-Observability-Demo-Deployment.md`**.

## Workspace size

The example `PipelineRun` uses **`emptyDir`**. For large repositories, bind `source` to a **PVC** via `volumeClaimTemplate` on the `PipelineRun`.

## Full design narrative

See **`OCP-4.18-Observability-Demo-Deployment.md`** — Tekton CI/CD sections (architecture, validation, troubleshooting, optional enhancements).
