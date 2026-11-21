# Phase 06 — Scale-out: Kafka, service extraction, workflows, analytics, deployment

## Goal
Run the platform as distributed services when scale or team structure demands it — without rewriting domain code. Events move to Kafka, chosen modules become independently deployable Spring Boot services, multi-step processes move to Temporal, analytics move to ClickHouse, and everything deploys to Kubernetes through GitOps.

**Do not start this phase just because phase 05 is done.** Start it only when at least one trigger in §Triggers is met, or the user explicitly asks. Each extraction is its own sub-task and can ship independently.

## Read before starting
- `CLAUDE.md`, `docs/PROGRESS.md`
- `docs/standards/01` (boundaries), `03` (versioning), `04` §8 (outbound calls), `05` §7 (tracing across services), `07` (all), `08`, `09`
- `docs/reference/event-catalogue.md`

## Triggers (record which one applies in an ADR before extracting)

| Module | Extract when |
| --- | --- |
| sales + sync | Sync/upload traffic needs independent scaling, or POS-related releases must ship more often |
| inventory | Movement volume makes its tables the main DB load or lock contention |
| reporting | Report queries affect transactional latency |
| finance | Separate team/release cadence or regulatory isolation |
| others | Only with a concrete scaling, ownership or compliance reason |

---

## Tasks

### T06-01 Kafka externalization (no extraction yet)
- Add Apache Kafka 4.x (KRaft) to local compose and Testcontainers.
- Add `spring-modulith-events-kafka`; annotate every event in `api/events` with `@Externalized("bmp.<module>.<aggregate>::#{aggregateId()}")`; wrap payloads in a CloudEvents envelope (`io.cloudevents:cloudevents-spring` or a small serializer) with `type = bmp.<module>.<event-kebab>.v1`, `tenantid`, `traceparent` extensions.
- Topics created by an admin job/Helm (not auto-create): partitions sized per topic (start 12), replication 3 in prod, retention 7 days; catalog/pricing/party topics **compacted**.
- Schema Registry (Apicurio, Java-based) with JSON Schema from AsyncAPI; compatibility BACKWARD; CI check.
- In the monolith, keep in-process listeners; Kafka is an additional publication target. Verify with an IT that every event reaches Kafka exactly once per commit (Modulith registry).

### T06-02 Service template and extraction procedure
Create `services/<name>/` as a Spring Boot app that depends on the module's Gradle subproject unchanged. For an extracted module:
1. Its own database (copy of the schema; data migration script + verification; RLS kept).
2. In-process listeners in that service replaced by Kafka listeners (`@KafkaListener` adapters in a new `adapter/in/kafka` package calling the **same** application handlers) with the inbox guard, consumer group `bmp.<service>`, manual ack after commit, retry topics + DLT (`@RetryableTopic`).
3. Other modules' synchronous calls to its `api` replaced by declarative HTTP clients (`@HttpExchange`) implementing the same `api` interface, with timeouts, Resilience4j circuit breaker and retries for idempotent calls; or gRPC (Spring gRPC) for the hot paths (price resolution, stock enquiry).
4. Local replicas fed from compacted topics.
5. Contract tests between consumer and provider (Spring Cloud Contract or Pact JVM).
6. Strangler cut-over behind the gateway route; dual-run reads compared for a period; then remove the module from the monolith.
Document the procedure in `docs/runbooks/extract-service.md` and apply it first to the module whose trigger fired.

### T06-03 API gateway and BFFs
- Spring Cloud Gateway (server WebMVC on virtual threads) as the single entry: JWT validation, tenant header injection, rate limiting (Redis), routing to monolith or extracted services, request size limits, CORS.
- POS BFF and back-office BFF as thin Spring Boot apps aggregating calls (only if the UI teams ask for them; otherwise skip and note in PROGRESS).

### T06-04 Temporal workflows
- Temporal (self-hosted via Helm or Temporal Cloud) with the Java SDK.
- Move these to workflows: the return flow (choreographed in phase 03; the workflow lives in a separate orchestration service that may call sales, payments and inventory), period close (phase 04) and onboarding (phase 05). Activities call module `api` commands, which are idempotent. Keep the old process-manager tables read-only for history.
- Workflow tests with `TestWorkflowEnvironment`.

### T06-05 Analytics on ClickHouse
- ClickHouse fed from Kafka (Kafka engine tables or a small Java consumer service writing batches); facts and materialized views mirroring reporting's read models.
- Reporting queries switch to ClickHouse via a `ReportingStore` port; Postgres implementation kept for small deployments (setting).

### T06-06 Distributed caching and rate limiting
Redis (Spring Data Redis) for idempotency records (optional move from Postgres), price snapshot caches, gateway rate limits.

### T06-07 Kubernetes deployment
- Helm charts per service in `deploy/helm/` (deployment, HPA, PDB, service, ingress, config, secrets via External Secrets, network policies, resource requests/limits, probes on management port, non-root, read-only FS).
- KEDA scaling on Kafka consumer lag for listener-heavy services.
- Argo CD application manifests; Argo Rollouts canary with Prometheus analysis (error rate, p95).
- Linkerd for mTLS between services (or Istio, ADR).
- Database: managed PostgreSQL per service with PITR; Flyway migrations run as a Kubernetes Job before rollout (expand-and-contract enforced).

### T06-08 Observability across services
OpenTelemetry Collector (tail sampling: keep errors and slow traces), Grafana dashboards per service and a cross-service "bill journey" dashboard (trace from sync upload to journal posted), alert rules with runbook links, SLOs (availability 99.9%, sale-to-ledger propagation < 5 s p95).

### T06-09 Silo tenancy tier
Ability to route a tenant to a dedicated database (tenant → datasource routing via `AbstractRoutingDataSource` keyed by tenant tier) and dedicated topics if contracted.

### T06-10 Resilience and chaos testing
Testcontainers + Toxiproxy tests: Kafka down, a service down, DB failover; prove the till keeps selling (sync queues), no events lost, no duplicates after recovery.

---

## Definition of Done (per extracted service)
- [ ] Trigger ADR written; extraction runbook followed.
- [ ] All existing flow ITs pass against the distributed deployment (Testcontainers compose or a kind cluster in CI).
- [ ] Contract tests between all consumers and the provider.
- [ ] Dashboards, alerts and runbooks exist; canary deployment demonstrated.
- [ ] Chaos tests pass; PROGRESS summary updated.
