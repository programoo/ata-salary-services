# Running ata-salary-services on Kubernetes (minikube)

This guide takes you from a stopped machine to the service answering requests
inside a local [minikube](https://minikube.sigs.k8s.io/) cluster, using the
manifests in [`k8s/`](k8s/).

| File | What it creates |
|------|-----------------|
| [`k8s/deployment.yaml`](k8s/deployment.yaml) | Deployment `salary-app`: 5 replicas of image `ata-salary-services:1.3`, port 8080, readiness/liveness probes on `GET /` |
| [`k8s/service.yaml`](k8s/service.yaml) | Service `salary-app` of type `NodePort`: port 80 inside the cluster, node port `30080` |

The image is never pushed to a registry. You build it locally and load it
straight into minikube, which is why the Deployment uses
`imagePullPolicy: IfNotPresent`.

Commands below work in both PowerShell and Git Bash unless noted.

## 1. Prerequisites

Install these once:

- [Docker Desktop](https://www.docker.com/products/docker-desktop/), running
- [minikube](https://minikube.sigs.k8s.io/docs/start/)
- [kubectl](https://kubernetes.io/docs/tasks/tools/)

Check them:

```bash
docker version
```

```bash
minikube version
```

```bash
kubectl version --client
```

## 2. Start the cluster

```bash
minikube start --driver=docker --cpus=4 --memory=4096
```

The Deployment asks for 5 x 512Mi memory and 5 x 200m CPU, so give minikube
at least 4 GB and 2 CPUs. If it has less, some pods stay `Pending` (see
[Troubleshooting](#troubleshooting)).

`--cpus` and `--memory` only take effect when the cluster is first created.
To change them later, run `minikube delete` and start again.

Confirm kubectl is talking to minikube:

```bash
kubectl config current-context
```

```bash
kubectl get nodes
```

You should see one node named `minikube` with status `Ready`.

## 3. Build the image and load it into minikube

From the project root:

```bash
docker build -t ata-salary-services:1.5 .
```

The [`Dockerfile`](Dockerfile) is multi-stage: Maven builds the jar, then it
is copied into a Java 21 JRE image. No local Maven or JDK is needed.

minikube runs its own container runtime, so an image in your local Docker
is not visible to the cluster until you load it:

```bash
minikube image load ata-salary-services:1.5
```

Check that it arrived:

```bash
minikube image ls
```

Look for `docker.io/library/ata-salary-services:1.5` in the list.

## 4. Deploy

```bash
kubectl apply -f k8s/
```

Wait for all replicas to become ready (the app needs about 10-30 seconds to
start and import its seed data):

```bash
kubectl rollout status deployment/salary-app
```

```bash
kubectl get pods -l app=salary-app
```

All 5 pods should show `READY 1/1` and `STATUS Running`.

## 5. Call the service

With the Docker driver on Windows (and macOS), the minikube node IP is not
reachable from your host, so `http://<node-ip>:30080` will not work directly.
Use one of these instead.

### Option A: port-forward (simplest)

```bash
kubectl port-forward service/salary-app 8080:80
```

Leave it running, then in another terminal:

```bash
curl http://localhost:8080/
```

Expected response: `{"status":"OK"}`. The app is now on the same URL as
`mvn spring-boot:run`, so the [Postman collection](postman/) and the
`ata-order-web` setting `VITE_API_BASE_URL=http://localhost:8080/api` work
unchanged.

Note that `port-forward` sends all traffic to a single pod, so it does not
show load balancing across replicas.

### Option B: minikube service tunnel

```bash
minikube service salary-app --url
```

It prints a URL such as `http://127.0.0.1:54321`. Keep that terminal open
and call the printed URL. This goes through the Service, so requests are
spread across all 5 pods.

### Try the API

```bash
curl "http://localhost:8080/atadev/job_data?salary[gte]=120000&size=5"
```

```bash
curl "http://localhost:8080/api/orders?page=0&size=5"
```

In PowerShell, use `curl.exe` instead of `curl` (which is an alias for
`Invoke-WebRequest` there).

## 6. Deploy a new version

The Deployment uses a fixed tag with `IfNotPresent`, so rebuilding the image
under the same tag does **not** update running pods. Use a new tag each time:

```bash
docker build -t ata-salary-services:1.4 .
```

```bash
minikube image load ata-salary-services:1.4
```

```bash
kubectl set image deployment/salary-app salary-app=ata-salary-services:1.4
```

```bash
kubectl rollout status deployment/salary-app
```

Then update the `image:` line in [`k8s/deployment.yaml`](k8s/deployment.yaml)
(and the build comment at the bottom of the `Dockerfile`) so the files match
the cluster. Undo a bad rollout with:

```bash
kubectl rollout undo deployment/salary-app
```

## 7. Everyday commands

| Task | Command |
|------|---------|
| List pods | `kubectl get pods -l app=salary-app -o wide` |
| Logs of one pod | `kubectl logs <pod-name>` |
| Follow logs of all pods | `kubectl logs -f -l app=salary-app --prefix` |
| Pod details and events | `kubectl describe pod <pod-name>` |
| Shell inside a pod | `kubectl exec -it <pod-name> -- sh` |
| Scale replicas | `kubectl scale deployment/salary-app --replicas=2` |
| Restart all pods | `kubectl rollout restart deployment/salary-app` |
| Kubernetes dashboard | `minikube dashboard` |

`kubectl scale` changes only the live cluster; the next `kubectl apply -f k8s/`
sets it back to the `replicas` value in the YAML.

## 8. Stop and clean up

Remove the app but keep the cluster:

```bash
kubectl delete -f k8s/
```

Stop the cluster (keeps its state and loaded images for next time):

```bash
minikube stop
```

Next time, `minikube start` and `kubectl apply -f k8s/` bring it back; you
only need to rebuild and reload the image if the code changed.

Delete the cluster entirely (removes loaded images too):

```bash
minikube delete
```

## Things to know

- **Each pod has its own database.** SQLite lives at `/app/data/salary.db`
  inside the container, with no volume mounted. Every pod imports the seed
  data on startup, and the data is lost when a pod restarts. That is fine
  for this read-only service, but anything written to one pod is not seen by
  the others.
- **Health probes use `GET /`.** A pod is only added to the Service once `/`
  returns `200`. Liveness failures restart the container.
- **CORS** allows only `http://localhost:5173` by default. To allow another
  origin, set an env var on the container, for example
  `APP_CORS_ALLOWED_ORIGINS=http://localhost:3000` (Spring maps it to
  `app.cors.allowed-origins`).

## Troubleshooting

| Symptom | Likely cause and fix |
|---------|----------------------|
| `ErrImagePull` / `ImagePullBackOff` | The image isn't in minikube, so Kubernetes tried Docker Hub. Run `minikube image load ata-salary-services:<tag>` and check the tag matches the Deployment exactly. |
| Pods stuck in `Pending` | Not enough CPU/memory. `kubectl describe pod <pod-name>` shows `Insufficient memory`. Scale down (`kubectl scale deployment/salary-app --replicas=2`) or recreate minikube with more memory. |
| `CrashLoopBackOff` | The app failed to start. Check `kubectl logs <pod-name> --previous`. |
| `OOMKilled` in `kubectl describe pod` | The JVM exceeded the 1Gi limit. Raise `resources.limits.memory` in the Deployment. |
| Pods `Running` but `READY 0/1` for a long time | The readiness probe is failing. Check `kubectl describe pod <pod-name>` events and the pod logs. |
| `curl http://<minikube ip>:30080` times out | Expected with the Docker driver on Windows/macOS. Use port-forward or `minikube service` (step 5). |
| New code not showing after rebuild | The tag didn't change, so pods still run the old image. See step 6. |
| `kubectl` talks to the wrong cluster | Run `kubectl config use-context minikube`. |
