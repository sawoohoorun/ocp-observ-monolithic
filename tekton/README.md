# Tekton delivery pipeline (OpenShift Pipelines)

This folder contains a **demo-friendly** Tekton `Pipeline` that automates clone → layout check → **apply app YAML from Git** → **OpenShift `BuildConfig` builds** (backend then frontend) → rollout waits → in-cluster smoke test → summary.

**Prerequisite:** [OpenShift Pipelines](https://docs.redhat.com/en/documentation/openshift_container_platform/4.18/html/cicd/pipelines) (Tekton) installed on the cluster. The pipeline assumes **operators, Tempo, and the OpenTelemetry Collector** are already applied (`openshift/00`–`11`); it does **not** reinstall them.

## File tree

| File | Purpose |
|------|---------|
| `00-serviceaccount.yaml` | `ServiceAccount` `dev-pipeline` |
| `01-rbac.yaml` | Namespace `Role` + `RoleBinding` (builds, deploys, routes, imagestreams, etc.) |
| `02-task-prepare-demo-repo-and-apply.yaml` | **One Task:** git clone, verify layout, `oc apply` Postgres + app manifests (single pod — shared workspace) |
| `05-task-oc-start-build.yaml` | `oc start-build --wait` for a `BuildConfig` |
| `06-task-oc-rollout-status.yaml` | `oc rollout status` for a `Deployment` |
| `07-task-emit-summary.yaml` | Print routes, `ImageStreamTag`s, Jaeger hints (`finally`) |
| `10-pipeline.yaml` | `Pipeline` `dev-delivery` |
| `11-pipelinerun.yaml` | Example `PipelineRun` with `emptyDir` workspace |
| `20-task-smoketest.yaml` | In-cluster HTTP smoke + optional collector log grep |

## Apply order

From the repo root (after namespace and observability stack exist):

```bash
oc delete task git-clone-demo-repo verify-demo-repo-layout apply-openshift-app-manifests -n dev --ignore-not-found

oc apply -f tekton/00-serviceaccount.yaml
oc apply -f tekton/01-rbac.yaml
oc apply -f tekton/02-task-prepare-demo-repo-and-apply.yaml
oc apply -f tekton/05-task-oc-start-build.yaml
oc apply -f tekton/06-task-oc-rollout-status.yaml
oc apply -f tekton/07-task-emit-summary.yaml
oc apply -f tekton/20-task-smoketest.yaml
oc apply -f tekton/10-pipeline.yaml
# Optional: oc apply -f tekton/11-pipelinerun.yaml
```

Or `oc apply -f tekton/` (apply **Pipeline** after **Tasks** if the first apply misses ordering).

## Start a run (`tkn`)

```bash
tkn pipeline ls -n dev
tkn pipeline start dev-delivery -n dev \
  --showlog \
  -w name=source,emptyDir="" \
  -p git-url=https://github.com/sawoohoorun/ocp-observ-monolithic.git \
  -p git-revision=main \
  -p namespace=dev
```

Follow logs for an existing run:

```bash
oc get pipelineruns -n dev
tkn pipelinerun logs -f <pipelinerun-name> -n dev
```

## Private Git

Create a `Secret` of type `kubernetes.io/basic-auth` or `kubernetes.io/ssh-auth`, link it to the `ServiceAccount` (`secrets` field), and use an SSH URL or HTTPS with credentials per your cluster’s Git documentation. The sample clone step uses a public HTTPS URL.

## Images used by tasks

Tasks pull **`registry.redhat.io/openshift4/ose-cli`**, **`docker.io/alpine/git`** (clone + verify steps), and **`docker.io/curlimages/curl`** (smoke HTTP). Ensure pulls are allowed, or mirror and edit the task YAML.

## Git “dubious ownership” / Tekton creds warning

The clone step sets **`HOME=/tmp/git-home`** and **`git config --global safe.directory '*'`** so Git accepts the workspace when its owner UID does not match the step user (common with **restricted** + **emptyDir**).

A Tekton line such as **`unsuccessful cred copy: ".docker" … mkdir /.docker: permission denied`** is **harmless** for public HTTPS clones (non-root cannot write `/`); ignore it or use a cluster without Docker-credential copy into the step.

## Workspace sharing (why one Task for clone + verify + apply)

Under **PSA restricted**, each **TaskRun** gets a **different random UID**. The workspace volume root is often **not** world-traversable, so a **separate** verify/apply pod could not read files written by the clone pod (**`missing frontend-ui/`** even after a good clone). **Clone, verify, and `oc apply` run in one Task** (four steps, one pod) so the workspace stays on the same UID. Later tasks (builds, rollouts, smoke) do not need the workspace.

## Pod Security Admission (restricted)

Tasks set **`stepTemplate.securityContext`** (`capabilities.drop: ["ALL"]`, **`runAsNonRoot: true`**, **`seccompProfile: RuntimeDefault`**) and the **Pipeline** / sample **PipelineRun** set **`taskRunTemplate.podTemplate.securityContext`**. If **Tekton entrypoint** init containers still violate **restricted** on your operator version, upgrade **OpenShift Pipelines** or configure **`TektonConfig` `default-pod-template`** — see **Troubleshooting Tekton Pipeline** in **`OCP-4.18-Observability-Demo-Deployment.md`**.

## Workspace size

The example `PipelineRun` uses **`emptyDir`**. For large repositories, bind `source` to a **PVC** via `volumeClaimTemplate` on the `PipelineRun`.

## Full design narrative

See **`OCP-4.18-Observability-Demo-Deployment.md`** — Tekton CI/CD sections (architecture, validation, troubleshooting, optional enhancements).
