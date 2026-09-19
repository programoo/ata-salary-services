# Probes exercise: startup, readiness and liveness

How Kubernetes decides whether a `salary-app` pod should get traffic or be
restarted, and what you see when each check fails. Assumes the app is deployed
as described in [readme_k8s.md](readme_k8s.md).

## Three probes, three questions

The kubelet (the agent on each node) calls these HTTP endpoints on every pod,
over and over. Each probe asks a different question and has a different
consequence when it fails:

| Probe | Question | When it fails | Endpoint |
|---|---|---|---|
| **startup** | Has the app finished starting? | Keeps waiting. After `failureThreshold` failures the container is restarted | `/actuator/health/liveness` |
| **readiness** | Should this pod get traffic right now? | Pod is **removed from the Service**. It keeps running and isn't restarted | `/actuator/health/readiness` |
| **liveness** | Is the process stuck beyond repair? | Container is **restarted** | `/actuator/health/liveness` |

The key difference: **readiness failing is harmless and reversible, while liveness
failing kills the container.** Most probe bugs come from mixing them up.

Order in a pod's life:

```
init containers (wait-for-seed)      no probes run yet
        |
startup probe                        liveness/readiness are paused until it passes once
        |
readiness + liveness                 both run for the rest of the pod's life, independently
```

## What changed from the old probes

Before, both probes called `GET /`, which only proved Tomcat could answer.
Now they use Spring Boot Actuator (`spring-boot-starter-actuator` in
[`pom.xml`](pom.xml)), which ties the answers to the app's real state:

| | Old | New |
|---|---|---|
| Readiness | `GET /` | `/actuator/health/readiness`: `UP` only after startup has fully finished **and** the database answers |
| Liveness | `GET /` | `/actuator/health/liveness`: `UP` unless the app declared itself broken |
| Slow start | `initialDelaySeconds: 30` on liveness (a guess) | A `startupProbe` that allows up to 150s, then hands over |
| Shutdown | Nothing | Spring switches readiness to `OUT_OF_SERVICE` as soon as the pod starts shutting down, so it stops getting traffic before it exits |

The settings are in
[`application.properties`](src/main/resources/application.properties):

```properties
management.endpoint.health.probes.enabled=true
management.endpoint.health.group.readiness.include=readinessState,db
management.endpoint.health.group.liveness.include=livenessState
```

And in [`k8s/deployment.yaml`](k8s/deployment.yaml):

```yaml
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 5
  failureThreshold: 30        # 30 x 5s = up to 150s to start
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: http }
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 3         # out of the Service after ~15s of failures
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 3         # restarted after ~30s of failures
```

**Time to react ≈ `periodSeconds` × `failureThreshold`.** A single failed
check does nothing, and `successThreshold` (default 1) passes needed to recover.
A call that takes longer than `timeoutSeconds` (default **1s**) counts as a
failure, which is why it's raised to 3s here: a database check can be slow.

### Why the database is in readiness but not liveness

- **Readiness includes `db`:** a pod that can't reach Postgres would only
  return errors, so it's better to take it out of the Service.
- **Liveness excludes `db`:** if Postgres goes down, restarting 5 healthy
  app pods won't bring it back. It only adds a restart storm on top of the
  outage. **Never put external dependencies in liveness.**

There is a trade-off: all 5 pods share one database, so when it's down they all
become unready at once and the Service has no pods at all. Callers get "connection
refused" instead of a `500` error. Both are outages; which one you prefer is a design choice.

## Deploy it

This change needs a new image (`1.7`) because the app itself changed. Start
Docker Desktop and the cluster first (step 2 of [readme_k8s.md](readme_k8s.md)).

```bash
docker build -t ata-salary-services:1.7 .
```

```bash
minikube image load ata-salary-services:1.7
```

The seed Job uses the same image, so recreate it first as in step 7 of
[readme_k8s.md](readme_k8s.md). No entity changed, so this is quick:

```bash
kubectl delete job salary-seed
```

```bash
kubectl apply -f k8s/seed-job.yaml
```

```bash
kubectl apply -f k8s/deployment.yaml
```

```bash
kubectl rollout status deployment/salary-app
```

## Look at it

The configured probes, as Kubernetes sees them:

```bash
kubectl describe deployment salary-app
```

Look for these lines:

```
Liveness:   http-get http://:http/actuator/health/liveness delay=0s timeout=3s period=10s #success=1 #failure=3
Readiness:  http-get http://:http/actuator/health/readiness delay=0s timeout=3s period=5s #success=1 #failure=3
Startup:    http-get http://:http/actuator/health/liveness delay=0s timeout=1s period=5s #success=1 #failure=30
```

Call the endpoints yourself through the Service (in a second terminal):

```bash
kubectl port-forward service/salary-app 8080:80
```

```bash
curl http://localhost:8080/actuator/health
```

```json
{"status":"UP","groups":["liveness","readiness"]}
```

```bash
curl http://localhost:8080/actuator/health/readiness
```

```json
{"status":"UP","components":{"db":{"status":"UP"},"readinessState":{"status":"UP"}}}
```

```bash
curl http://localhost:8080/actuator/health/liveness
```

```json
{"status":"UP","components":{"livenessState":{"status":"UP"}}}
```

The HTTP status code is the only part the kubelet looks at: `200` = pass,
`503` = fail. The JSON body is for humans.

## The demo switch

To let you fail a probe on purpose, the Deployment sets
`APP_PROBE_DEMO_ENABLED=true`, which turns on
[`ProbeDemoController`](src/main/java/com/ata/salaryservices/controller/ProbeDemoController.java):

| Call | Effect on that one pod |
|---|---|
| `POST /demo/probes/readiness/refuse` | Readiness returns `503 OUT_OF_SERVICE` |
| `POST /demo/probes/readiness/accept` | Readiness returns `200 UP` again |
| `POST /demo/probes/liveness/break` | Liveness returns `503 DOWN` (no undo, which is what liveness is for) |

These only affect the pod that receives the call, so talk to **one pod
directly**, not the Service (which would pick a random pod). Port-forwarding
to a pod works even when it's unready, because it skips the Service.

Keep one terminal watching the pods for the whole exercise:

```bash
kubectl get pods -l app=salary-app -w
```

## Exercise 1: readiness takes a pod out of the Service without restarting it

### Step 1: pick a pod and connect to it directly

```bash
kubectl get pods -l app=salary-app
```

Copy one name, for example `salary-app-7d9f8c6b5-abcde`. In a new terminal:

```bash
kubectl port-forward pod/<pod-name> 8081:8080
```

### Step 2: see which pods the Service sends traffic to

The Service keeps its list of ready pods in an EndpointSlice:

```bash
kubectl get endpointslices -l kubernetes.io/service-name=salary-app
```

`ENDPOINTS` shows 5 pod IPs. Note your pod's IP (`kubectl get pod <pod-name> -o wide`).

### Step 3: make the pod refuse traffic

```bash
curl -X POST http://localhost:8081/demo/probes/readiness/refuse
```

```bash
curl -i http://localhost:8081/actuator/health/readiness
```

```
HTTP/1.1 503
{"status":"OUT_OF_SERVICE","components":{"db":{"status":"UP"},"readinessState":{"status":"OUT_OF_SERVICE"}}}
```

### Step 4: watch Kubernetes react

After about 15 seconds (5s × 3 failures), the watch terminal shows the pod
change from `1/1` to `0/1` READY, while `STATUS` stays `Running` and
`RESTARTS` stays `0`:

```
salary-app-7d9f8c6b5-abcde   0/1     Running   0          12m
```

Its IP is gone from the Service:

```bash
kubectl get endpointslices -l kubernetes.io/service-name=salary-app
```

The reason is in the pod's events:

```bash
kubectl describe pod <pod-name>
```

```
Warning  Unhealthy  ...  Readiness probe failed: HTTP probe failed with statuscode: 503
```

The pod is alive and still answers on your direct port-forward, but no
Service traffic reaches it. This is how you'd take a pod out of rotation for
maintenance or while it's overloaded.

### Step 5: bring it back

```bash
curl -X POST http://localhost:8081/demo/probes/readiness/accept
```

About 5 seconds later (one passing check is enough), the pod shows `1/1` again
and its IP is back in the EndpointSlice.

## Exercise 2: liveness restarts the container

### Step 1: break the pod

Using the same port-forward:

```bash
curl -X POST http://localhost:8081/demo/probes/liveness/break
```

```bash
curl -i http://localhost:8081/actuator/health/liveness
```

```
HTTP/1.1 503
{"status":"DOWN","components":{"livenessState":{"status":"DOWN"}}}
```

### Step 2: watch the restart

Within about 30 seconds (10s × 3 failures), the watch terminal shows
`RESTARTS` change to `1`. The port-forward dies with it, because the container it pointed at
is gone.

```bash
kubectl describe pod <pod-name>
```

```
Warning  Unhealthy  ...  Liveness probe failed: HTTP probe failed with statuscode: 503
Normal   Killing    ...  Container salary-app failed liveness probe, will be restarted
```

The **pod** is the same (same name, same IP); only the **container** inside it
was restarted. The new JVM starts with a fresh state, so liveness is `UP`
again. The startup probe runs again first, so you'll briefly see `0/1`.

Logs from before the restart are still available:

```bash
kubectl logs <pod-name> --previous
```

In a real app, the code sets `LivenessState.BROKEN` itself when it detects
something it can't recover from, such as a deadlock or a corrupted in-memory
cache.

## Exercise 3: the database goes down

This is the real-world version of Exercise 1, and it shows why `db` is only in
readiness.

### Step 1: stop Postgres

Scaling the StatefulSet to 0 stops the pod but keeps the PVC, so no data is lost:

```bash
kubectl scale statefulset postgres --replicas=0
```

### Step 2: watch

Within about 15–30 seconds, **all 5** app pods show `0/1` READY. `RESTARTS` doesn't change.

```bash
kubectl get endpointslices -l kubernetes.io/service-name=salary-app
```

The Service has no endpoints. Port-forward to any pod and look:

```bash
curl http://localhost:8081/actuator/health/readiness
```

```json
{"status":"DOWN","components":{"db":{"status":"DOWN"},"readinessState":{"status":"UP"}}}
```

This `curl` can hang for up to 30 seconds first, because the connection pool (Hikari)
waits that long for a connection before giving up. The kubelet doesn't wait that
long: after 3s (`timeoutSeconds`) it counts the check as failed, and the
events say `context deadline exceeded` instead of `statuscode: 503`.

`readinessState` is still `UP` (the app is fine); `db` is `DOWN`, which makes
the whole group `DOWN`. Liveness is still `UP`, so nothing restarts.

### Step 3: bring Postgres back

```bash
kubectl scale statefulset postgres --replicas=1
```

When `postgres-0` is ready, the app pods go back to `1/1` on their own, with
no restarts and no manual steps.

> **Try the wrong way (optional):** in `application.properties`, set
> `management.endpoint.health.group.liveness.include=livenessState,db`,
> build it as `1.8`, deploy, and repeat this exercise. The pods now restart
> every ~30s while the database is down (`RESTARTS` climbs, then
> `CrashLoopBackOff`). Put the line back afterwards.

## Pitfall: a health group without `include`

While building this, the first version had only:

```properties
management.endpoint.health.group.liveness.show-components=always
```

That line **creates** a `liveness` group, replacing Spring's built-in one, and
a group without `include` contains **every** health check: `db`, `diskSpace`,
even `readinessState`. So refusing readiness also failed liveness, and the pod
would have been restarted. The test in
[`ProbeDemoControllerTest`](src/test/java/com/ata/salaryservices/controller/ProbeDemoControllerTest.java)
caught it. Always set `include` when you configure a group, and check what's in it:

```bash
curl http://localhost:8080/actuator/health/liveness
```

## Common probe mistakes

| Mistake | What happens |
|---|---|
| External dependency (DB, other service) in liveness | Outage elsewhere → all your pods restart in a loop |
| Liveness without a startup probe and too little `initialDelaySeconds` | Slow JVM start gets killed before it's up → `CrashLoopBackOff` forever |
| Readiness and liveness on the same endpoint | Can't take a pod out of rotation without also restarting it |
| Default `timeoutSeconds: 1` with a slow check | A briefly slow response counts as a failure → pods flicker unready or restart under load |
| Probe endpoint doing heavy work | Probes run every few seconds on every pod, so they add real load |
| No readiness probe at all | New pods get traffic before Spring has started → errors during every rollout |

## Readiness makes rollouts safe

During `kubectl rollout`, the Deployment only removes an old pod after a new
one is **ready**. With a meaningful readiness probe, a broken image
(for example, one that can't reach the database) never becomes ready, so the rollout
stalls and the old pods keep serving. Try it: a bad image stays at
`0/1`, `kubectl rollout status` waits, and `kubectl rollout undo
deployment/salary-app` recovers. The next topic,
**rolling update strategy** (`maxSurge`, `maxUnavailable`), builds on this.

## Quick reference

| Task | Command |
|---|---|
| Show probe settings | `kubectl describe deployment salary-app` |
| Why is a pod unready / restarting? | `kubectl describe pod <pod-name>` (Events at the bottom) |
| Which pods get Service traffic | `kubectl get endpointslices -l kubernetes.io/service-name=salary-app` |
| Watch READY and RESTARTS change | `kubectl get pods -l app=salary-app -w` |
| Logs before the last restart | `kubectl logs <pod-name> --previous` |
| Call one pod directly | `kubectl port-forward pod/<pod-name> 8081:8080` |
| Check probe endpoints | `curl http://localhost:8080/actuator/health/readiness` |

## Troubleshooting

| Symptom | Likely cause and fix |
|---|---|
| `404` on `/actuator/health/...` | The running image is older than `1.7`. Check `kubectl get deployment salary-app -o wide` and rebuild/load the image. |
| `404` on `/demo/probes/...` | `APP_PROBE_DEMO_ENABLED` isn't `"true"` in the Deployment, or the image is old. |
| All pods `0/1`, no restarts | Readiness failing everywhere, usually the database. `curl .../actuator/health/readiness` on one pod shows which component is `DOWN`. |
| `RESTARTS` keeps climbing | Liveness or startup failing. `kubectl describe pod` shows which; `kubectl logs --previous` shows why. |
| Pod restarted during startup | Startup took longer than 150s. Raise `startupProbe.failureThreshold`, or the CPU limit (JVM startup is CPU-heavy). |
| Probe events say `context deadline exceeded` | The check took longer than `timeoutSeconds`. Look at what's slow (often the DB check). |

## Before real production

Remove the `APP_PROBE_DEMO_ENABLED` entry from
[`k8s/deployment.yaml`](k8s/deployment.yaml). Anyone who can reach the app
could otherwise take pods out of rotation or restart them.
