# Rolling update exercise: maxSurge, maxUnavailable and rollbacks

How a Deployment swaps old pods for new ones, how to control the pace, and
how to recover when a new version is broken. Assumes the app is deployed as
described in [readme_k8s.md](readme_k8s.md) and that you've done
[readme_probes_k8s.md](readme_probes_k8s.md), because readiness is what makes
all of this safe.

## How a rollout works

A Deployment doesn't manage pods directly. It manages **ReplicaSets**, and each
ReplicaSet runs pods from one version of the pod template:

```
Deployment salary-app
 ├── ReplicaSet salary-app-5dd757f49b   (old template)  2 → 1 → 0 pods
 └── ReplicaSet salary-app-6984476b9b   (new template)  0 → 1 → 2 pods
```

Any change under `spec.template` (image, env var, probe, the
`restartedAt` annotation from `kubectl rollout restart`...) creates a new
ReplicaSet. The Deployment then scales the new one up and the old one down,
step by step. The old ReplicaSet isn't deleted; it stays at 0 pods so you can
roll back to it.

Changes outside the template (`replicas`, `strategy`, `minReadySeconds`...) don't
start a rollout.

## The two knobs

| Setting          | Question                                                         | Effect                                                   |
|------------------|------------------------------------------------------------------|----------------------------------------------------------|
| `maxSurge`       | How many pods **above** `replicas` may exist during the rollout? | Higher = faster, but needs spare CPU/memory              |
| `maxUnavailable` | How many pods **below** `replicas` may be unready?               | Higher = faster and no spare capacity needed, but you serve with fewer pods |

They can't both be `0` (the rollout could never make a move). Values are a
number or a percentage of `replicas`. Percentages are rounded: **`maxSurge`
rounds up, `maxUnavailable` rounds down.**

### What you had before (the default)

Without a `strategy` block, a Deployment uses `maxSurge: 25%` and
`maxUnavailable: 25%`. With 2 replicas:

- `maxSurge` = 25% of 2 = 0.5, rounded **up** → **1**
- `maxUnavailable` = 25% of 2 = 0.5, rounded **down** → **0**

So you already had "one extra pod, never fewer than 2 serving". That's exactly
what you saw when the `1.8` image was missing: 2 old pods `Running`, 1 new pod
in `ImagePullBackOff`, and the rollout stuck there without breaking anything.

With 5 replicas the same defaults give surge 2 and unavailable 1, a different
behaviour. That's why the manifest now spells the values out.

### Common choices

| `maxSurge` / `maxUnavailable` | Behaviour                                                         | Use when                                  |
|-------------------------------|-------------------------------------------------------------------|-------------------------------------------|
| `1` / `0`                     | Add one new pod, then remove one old pod. Capacity never drops   | Default choice for a web app (used here)  |
| `0` / `1`                     | Remove one old pod, then add one new pod. No extra resources     | Cluster is full, brief capacity drop is OK |
| `100%` / `0`                  | Start all new pods at once, then remove all old ones              | Fastest safe rollout, needs double resources |
| `type: Recreate`              | Kill **all** old pods, then start new ones. Downtime              | Old and new version must never run together (e.g. incompatible DB migrations) |

## What changed in the manifest

In [`k8s/deployment.yaml`](k8s/deployment.yaml):

```yaml
spec:
  replicas: 2
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  minReadySeconds: 10
  progressDeadlineSeconds: 240
  revisionHistoryLimit: 5
  template:
    spec:
      terminationGracePeriodSeconds: 45
      containers:
        - name: salary-app
          lifecycle:
            preStop:
              sleep:
                seconds: 5
```

| Setting                             | Default | Here | Why                                                                                                          |
|-------------------------------------|---------|------|--------------------------------------------------------------------------------------------------------------|
| `strategy`                          | 25%/25% | 1/0  | Same result as before with 2 replicas, but stays predictable if `replicas` changes                          |
| `minReadySeconds`                   | 0       | 10   | A new pod must stay ready for 10s before it counts. Catches a pod that crashes right after passing readiness |
| `progressDeadlineSeconds`           | 600     | 240  | Report a stuck rollout after 4 min instead of 10. Must be longer than one pod's startup (up to 150s)        |
| `revisionHistoryLimit`              | 10      | 5    | Number of old ReplicaSets kept for rollback                                                                  |
| `terminationGracePeriodSeconds`     | 30      | 45   | Room for the preStop sleep plus Spring's graceful shutdown                                                   |
| `preStop` sleep                     | none    | 5s   | Zero-downtime shutdown, see below                                                                            |

### Why the preStop sleep

When a pod is deleted, two things start **at the same time**:

1. The kubelet sends `SIGTERM` to the app.
2. The pod is removed from the Service's EndpointSlice, and kube-proxy on every
   node updates its routing rules.

Step 2 takes a moment. If the app has already stopped accepting connections
by then, a few requests still get routed to it and fail. The `preStop` sleep
delays the `SIGTERM` by 5 seconds, so routing is updated first.

After that, Spring Boot (3.4 and later) shuts down gracefully by default:
it stops taking new requests, finishes the ones in progress (up to 30s), then
exits. `terminationGracePeriodSeconds` is the total budget from the start of
`preStop` until Kubernetes kills the container: 5 + 30 + margin = 45.

## Deploy it

No new image is needed. Only the manifest changed.

```bash
kubectl apply -f k8s/deployment.yaml
```

Because `terminationGracePeriodSeconds` and `lifecycle` are under
`spec.template`, **this apply starts a rollout by itself**. Watch it:

```bash
kubectl rollout status deployment/salary-app
```

Check the new settings:

```bash
kubectl describe deployment salary-app
```

Look for:

```
StrategyType:           RollingUpdate
MinReadySeconds:        10
RollingUpdateStrategy:  0 max unavailable, 1 max surge
```

## Set up your terminals

Open these and keep them running for all the exercises.

**Terminal 1: pods**

```bash
kubectl get pods -l app=salary-app -w
```

**Terminal 2: ReplicaSets.** `DESIRED`, `CURRENT` and `READY` show the hand-over
between old and new:

```bash
kubectl get rs -l app=salary-app -w
```

**Terminal 3: a tunnel to the Service** (not `kubectl port-forward`: that
connects to one pod, and breaks when the rollout removes that pod):

```bash
minikube service salary-app --url
```

It prints a URL such as `http://127.0.0.1:54321`. Leave it running.

**Terminal 4: traffic.** In Git Bash, with your URL:

```bash
while true; do curl -s -o /dev/null -w "%{http_code} " --max-time 2 http://127.0.0.1:54321/actuator/health; sleep 0.5; done
```

Or in PowerShell:

```powershell
while ($true) { try { $r = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://127.0.0.1:54321/actuator/health; Write-Host -NoNewline "$($r.StatusCode) " } catch { Write-Host -NoNewline "FAIL " }; Start-Sleep -Milliseconds 500 }
```

It should print a steady stream of `200`. Anything else during an exercise is a
request a user would have seen fail.

## Exercise 1: watch a zero-downtime rollout

### Step 1: start a rollout

`rollout restart` changes an annotation in the pod template, which creates a new
ReplicaSet with the same image. It's the easiest way to trigger a rollout:

```bash
kubectl rollout restart deployment/salary-app
```

Record why you did it. This shows up in the rollout history (Exercise 4):

```bash
kubectl annotate deployment/salary-app kubernetes.io/change-cause="exercise 1: restart" --overwrite
```

### Step 2: follow it step by step

In terminal 1 you should see this order:

```
salary-app-NEW-aaaaa   0/1   Init:0/1    0   0s     # surge: 3 pods now
salary-app-NEW-aaaaa   0/1   Running     0   5s     # startup probe running
salary-app-NEW-aaaaa   1/1   Running     0   40s    # ready, then 10s minReadySeconds
salary-app-OLD-xxxxx   1/1   Terminating 0   2d     # only now an old pod goes
salary-app-NEW-bbbbb   0/1   Init:0/1    0   0s     # next new pod
...
```

Terminal 2 shows the ReplicaSets trading places:

```
NAME                    DESIRED   CURRENT   READY
salary-app-OLD          2         2         2
salary-app-NEW          1         1         0
salary-app-NEW          1         1         1
salary-app-OLD          1         2         2     # scaling down, pod terminating
salary-app-OLD          1         1         1
salary-app-NEW          2         2         1
...
salary-app-OLD          0         0         0     # kept for rollback
salary-app-NEW          2         2         2
```

At no point are fewer than 2 pods ready, and never more than 3 pods exist.

### Step 3: check the traffic

Terminal 4 should show only `200` for the whole rollout. That's the combination of:

- readiness: new pods get traffic only when they're really ready
- `maxUnavailable: 0`: an old pod leaves only after its replacement is ready
- `preStop` sleep and graceful shutdown: old pods stop getting traffic before
  they stop answering

> **Try it without preStop (optional):** remove the `lifecycle` block, apply,
> and run `kubectl rollout restart` a few times. You may see an occasional
> non-`200` (or `FAIL`) while old pods terminate. It's a race, so it doesn't
> happen every time. Put the block back afterwards.

## Exercise 2: maxUnavailable instead of maxSurge

### Step 1: change the strategy

In [`k8s/deployment.yaml`](k8s/deployment.yaml), set:

```yaml
      maxSurge: 0
      maxUnavailable: 1
```

```bash
kubectl apply -f k8s/deployment.yaml
```

This alone doesn't start a rollout (`strategy` isn't part of the pod template),
so trigger one:

```bash
kubectl rollout restart deployment/salary-app
```

### Step 2: compare

Now terminal 1 shows the opposite order: an **old pod terminates first**, and
only then does a new pod start. For the ~1 minute a new pod needs to start,
only **1 pod** serves traffic, which is half your capacity.

Terminal 4 still shows `200`s: one pod is enough for a single curl loop. Under
real load, that single pod would take all the traffic. That's the trade-off:
no extra CPU/memory needed, but less capacity during the rollout.

### Step 3: put it back

Change the values back to `maxSurge: 1` and `maxUnavailable: 0`, then:

```bash
kubectl apply -f k8s/deployment.yaml
```

## Exercise 3: a broken rollout stops by itself

This is what happened to you with the missing `1.8` image, this time on purpose.

### Step 1: deploy an image that doesn't exist

```bash
kubectl set image deployment/salary-app salary-app=ata-salary-services:9.9
```

```bash
kubectl annotate deployment/salary-app kubernetes.io/change-cause="exercise 3: bad image 9.9" --overwrite
```

`kubectl set image` changes the live Deployment only, not your YAML file.

### Step 2: watch it get stuck

Terminal 1:

```
salary-app-OLD-xxxxx   1/1   Running            0   5m
salary-app-OLD-yyyyy   1/1   Running            0   5m
salary-app-BAD-zzzzz   0/1   ImagePullBackOff   0   30s
```

`maxSurge: 1` allowed one new pod. It never becomes ready, and with
`maxUnavailable: 0` no old pod may leave until it does. The rollout can't take
another step. **Terminal 4 keeps showing `200`**: the old version still serves
everything.

### Step 3: see Kubernetes give up

```bash
kubectl rollout status deployment/salary-app
```

It waits. After `progressDeadlineSeconds` (240s) without progress, it ends with:

```
error: deployment "salary-app" exceeded its progress deadline
```

The same information is in the Deployment's conditions:

```bash
kubectl describe deployment salary-app
```

```
Conditions:
  Type           Status  Reason
  ----           ------  ------
  Available      True    MinimumReplicasAvailable
  Progressing    False   ProgressDeadlineExceeded
```

`Available: True` (users are fine) and `Progressing: False` (the rollout is
stuck). **Kubernetes does not roll back by itself.** The bad pod stays in
`ImagePullBackOff` until you act. A CI/CD pipeline typically runs
`kubectl rollout status --timeout=...` and, on failure, runs `rollout undo`.

### Step 4: roll back

```bash
kubectl rollout undo deployment/salary-app
```

The Deployment scales the previous ReplicaSet back up (its pods are already
running, so nothing changes there) and removes the bad pod. Check the image:

```bash
kubectl get deployment salary-app -o wide
```

`IMAGES` shows `ata-salary-services:1.9` again.

> **What if `maxUnavailable` were 1?** Kubernetes would have removed one old
> pod straight away to make room, leaving 1 serving pod for the whole time the
> rollout was stuck. With `maxUnavailable: 0`, a bad image costs you nothing but
> one stuck pod.

## Exercise 4: rollout history and going back further

### Step 1: see the revisions

```bash
kubectl rollout history deployment/salary-app
```

Something like (your numbers will differ):

```
REVISION  CHANGE-CAUSE
13        <none>
15        exercise 3: bad image 9.9
16        exercise 1: restart
```

Each revision is one ReplicaSet. Notes:

- The undo in Exercise 3 didn't create a new ReplicaSet. It scaled the previous
  one back up and gave it the **next revision number**. So "exercise 1:
  restart" moved from 14 to 16, and 14 is gone from the list.
- `CHANGE-CAUSE` is the `kubernetes.io/change-cause` annotation. The undo copied
  it back from that ReplicaSet, so it still says "exercise 1".
- Revisions with `<none>` are ones you created without annotating.
- Only the last `revisionHistoryLimit` (5) old ReplicaSets are kept.

### Step 2: inspect one revision

Pick a number from your list:

```bash
kubectl rollout history deployment/salary-app --revision=<n>
```

This prints that revision's pod template: image, env vars, probes. Use it to
find which revision to go back to.

### Step 3: roll back to a specific revision

Choose a revision that isn't the bad image:

```bash
kubectl rollout undo deployment/salary-app --to-revision=<n>
```

### Step 4: bring the cluster back in line with your YAML

`set image` and `undo` change only the live cluster. Your YAML file is still the
source of truth, and the next apply wins:

```bash
kubectl apply -f k8s/deployment.yaml
```

In real projects, **roll back by reverting the commit and deploying again**, and
use `rollout undo` for emergencies. Otherwise, the next deploy from Git
brings the broken version back.

## Exercise 5 (optional): pause a rollout

Pausing lets you make several changes and roll them out as one, or stop a
rollout halfway to look at the new version before continuing.

```bash
kubectl rollout pause deployment/salary-app
```

Make two template changes. Two harmless env vars are enough:

```bash
kubectl set env deployment/salary-app DEMO_STEP=one
```

```bash
kubectl set env deployment/salary-app DEMO_NOTE=two
```

Nothing happens in terminal 1: a paused Deployment records changes but doesn't
roll them out. (`kubectl rollout restart` refuses with `can't restart paused
deployment`.) Resume:

```bash
kubectl rollout resume deployment/salary-app
```

**One** rollout starts, containing both changes. Without the pause, you'd have
had two rollouts back to back. Remove the env vars afterwards (the trailing `-`
means "remove"). That's one more rollout:

```bash
kubectl set env deployment/salary-app DEMO_STEP- DEMO_NOTE-
```

Pausing is **not** a canary release. You can't choose "1 of 2 pods on the new
version and stay there". For that you need two Deployments behind one Service,
or a tool such as Argo Rollouts.

## A note on old and new versions running together

During every rolling update, **both versions serve traffic at the same time**
for a minute or so. Two consequences:

- **Database schema:** the old version must work with the new schema. That's why
  entity changes go through the seed Job first (step 7 of
  [readme_k8s.md](readme_k8s.md)), and why real projects make schema changes in
  backwards-compatible steps (add a column first, remove the old one in a
  later release).
- **API changes:** a browser may get a page from the new version and then call
  an API on an old pod. Keep APIs backwards compatible across one release.

If the two versions truly can't run together, use `strategy: type: Recreate`
and accept the downtime.

## Quick reference

| Task                                 | Command                                                                        |
|--------------------------------------|--------------------------------------------------------------------------------|
| Follow a rollout                     | `kubectl rollout status deployment/salary-app`                                |
| Rollout without changing the image   | `kubectl rollout restart deployment/salary-app`                               |
| Change the image (live only)         | `kubectl set image deployment/salary-app salary-app=ata-salary-services:<tag>` |
| Record why                           | `kubectl annotate deployment/salary-app kubernetes.io/change-cause="..." --overwrite` |
| History                              | `kubectl rollout history deployment/salary-app`                               |
| One revision's details               | `kubectl rollout history deployment/salary-app --revision=<n>`                |
| Undo the last rollout                | `kubectl rollout undo deployment/salary-app`                                  |
| Go back to a specific revision       | `kubectl rollout undo deployment/salary-app --to-revision=<n>`                |
| Pause / resume                       | `kubectl rollout pause deployment/salary-app` / `resume`                      |
| ReplicaSets with their image         | `kubectl get rs -l app=salary-app -o wide`                                    |
| Strategy and conditions              | `kubectl describe deployment salary-app`                                      |

## Troubleshooting

| Symptom                                               | Likely cause and fix                                                                                                                                         |
|-------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `exceeded its progress deadline`                      | A new pod never became ready. `kubectl get pods -l app=salary-app` shows which, `kubectl describe pod <pod-name>` shows why. Fix it, or `kubectl rollout undo`. |
| New pod `ImagePullBackOff`                            | The image tag isn't in minikube. `minikube image ls` and `minikube image load ata-salary-services:<tag>`.                                                   |
| New pod `CrashLoopBackOff`                            | The new version fails at startup. `kubectl logs <pod-name> --previous`. Often a schema mismatch: run the seed Job first (step 7 of [readme_k8s.md](readme_k8s.md)). |
| New pod stuck `0/1 Running`                           | Readiness failing. `curl .../actuator/health/readiness` on that pod (see [readme_probes_k8s.md](readme_probes_k8s.md)).                                     |
| New pod `Pending`                                     | No room for the surge pod. Free resources, or use `maxSurge: 0` / `maxUnavailable: 1`.                                                                     |
| `apply` did nothing visible                           | You only changed fields outside the pod template (`strategy`, `replicas`...). Run `kubectl rollout restart` if you want a rollout.                          |
| `apply` undid my `set image` / `rollout undo`         | Expected: the YAML file wins. Put the version you want in the file.                                                                                         |
| `can't restart paused deployment`                     | `kubectl rollout resume deployment/salary-app`.                                                                                                             |
| Rollout finishes, but some requests failed            | Pods stopped before routing was updated. Check the `preStop` sleep and that `terminationGracePeriodSeconds` covers it.                                      |
