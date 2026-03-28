# Manual tracing layout (`single-span` branch)

The detailed **single-span** span model (**`app: get order`**, **`step: prepare request`**, **`ApiServerObservationConfig`**, manual **`db: select …`** spans, Micrometer OTLP) lives on Git branch **`single-span`**.

**This repository branch (`auto-instrumentation`)** uses **Java auto-instrumentation** from the OpenTelemetry Operator instead. See **`docs/auto-instrumentation.md`**.

To read the full manual tracing document as it exists on **`single-span`**, check out that branch and open **`docs/single-span-tracing.md`** there.
