# Running ata-salary-services on Kubernetes (minikube)

This guide takes you from a stopped machine to the service answering requests
inside a local [minikube](https://minikube.sigs.k8s.io/) cluster, using the
manifests in [`k8s/`](k8s/).

| File | What it creates |
|------|-----------------|
| [`k8s/deployment.yaml`](k8s/deployment.yaml) | Deployment `salary-app`: 5 replicas of image `ata-salary-services:1.6`, port 8080, readiness/liveness probes on `GET /`. Connects to Postgres. |
| [`k8s/service.yaml`](k8s/service.yaml) | Service `salary-app` of type `NodePort`: port 80 inside the cluster, node port `30080` |
| [`k8s/postgres.yaml`](k8s/postgres.yaml) | Secret `postgres` (DB name, user, password), headless Service `postgres`, and StatefulSet `postgres` (1 replica of `postgres:16-alpine`) with a 1Gi PersistentVolumeClaim |
| [`k8s/seed-job.yaml`](k8s/seed-job.yaml) | Job `salary-seed`: runs the app image once to create the tables and import the seed data, then exits |

How the pieces fit together:

```
               kubectl port-forward / minikube service
                              |
                  Service salary-app (NodePort)
                              |
        salary-app pods x5 (Deployment)
                              |
                  Service postgres (headless)
                              |
  Job salary-seed  ----->  postgres-0 (StatefulSet)
  (runs once:                 |
   tables + data)      PVC data-postgres-0
                              |
                       PersistentVolume (minikube "standard" storage)
```

All 5 app pods share one database, so they all see the same data. The data
survives pod restarts because Postgres keeps its files on a volume, not
inside the container.

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
docker build -t ata-salary-services:1.6 .
```

The [`Dockerfile`](Dockerfile) is multi-stage: Maven builds the jar, then it
is copied into a Java 21 JRE image. No local Maven or JDK is needed.

minikube runs its own container runtime, so an image in your local Docker
is not visible to the cluster until you load it:

```bash
minikube image load ata-salary-services:1.6
```

Check that it arrived:

```bash
minikube image ls
```

Look for `docker.io/library/ata-salary-services:1.6` in the list.

The `postgres:16-alpine` image is pulled from Docker Hub by the cluster
itself the first time, so you don't need to load it.

## 4. Deploy

```bash
kubectl apply -f k8s/
```

This creates everything at once, but the pieces still start in the right
order thanks to **init containers**. A pod's init containers must finish
before its main container starts.

1. `postgres-0` starts. Kubernetes first creates the PVC `data-postgres-0`,
   and minikube provisions a volume for it.
2. The `salary-seed` Job's init container `wait-for-postgres` loops on
   `pg_isready`. Once Postgres answers, the Job runs the app without a web
   server (`SPRING_MAIN_WEB_APPLICATION_TYPE=none`). Hibernate creates the
   tables, the loaders import the seed data, and the process exits.
3. Each `salary-app` pod's init container `wait-for-seed` loops until both
   tables have rows. Only then does the app container start. It runs with
   `ddl-auto=validate`, so it checks the tables match the entities but never
   changes them, and it never imports data.

Why a Job? If each of the 5 replicas created the schema and imported data
itself, they would race each other on an empty database and could insert the
seed rows several times. One Job does it once.

Watch it happen (Ctrl+C to stop watching):

```bash
kubectl get pods -w
```

The `salary-app` pods show `Init:0/1`, `salary-seed` goes to `Completed`, and
then the app pods move to `Running`. Then:

```bash
kubectl rollout status deployment/salary-app
```

```bash
kubectl get pods,pvc,job
```

Expect `postgres-0` `1/1 Running`, 5 `salary-app` pods `1/1 Running`,
`salary-seed` `Completed`, and `data-postgres-0` `Bound`. Check what the Job
did:

```bash
kubectl logs job/salary-seed
```

The log should end with `Imported 3777 salary records` and `Imported 123 orders`.

**Coming from the SQLite version?** Just run `kubectl apply -f k8s/`. The
Deployment does a rolling update: the old pods keep serving until the new
ones are past their init container and ready.

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

## 6. Work with the database

### Open a SQL shell

`kubectl exec` runs a command inside a running container, and the Postgres
image already includes `psql`:

```bash
kubectl exec -it postgres-0 -- psql -U salary -d salary
```

Try `\dt` (list tables), `SELECT count(*) FROM orders;`, and `\q` to quit.

### Prove the data persists

Add a row, delete the Postgres pod, and check the row is still there:

```bash
kubectl exec postgres-0 -- psql -U salary -d salary -c "CREATE TABLE marker(note text); INSERT INTO marker VALUES ('still here');"
```

```bash
kubectl delete pod postgres-0
```

The StatefulSet immediately recreates a pod with the **same name**, and it
reattaches the **same PVC**. Wait for it:

```bash
kubectl wait --for=condition=ready pod/postgres-0 --timeout=120s
```

```bash
kubectl exec postgres-0 -- psql -U salary -d salary -c "SELECT * FROM marker;"
```

The app pods reconnect by themselves once Postgres is back. Remove the test
table with `DROP TABLE marker;`. With a plain Deployment and no volume, the
new pod would have started with an empty data directory.

### Inspect the storage

```bash
kubectl get pvc,pv
```

```bash
kubectl describe pvc data-postgres-0
```

The PVC is a *request* for storage, and it is bound to a PV, the actual
storage. minikube's `standard` StorageClass created the PV for you as a
directory inside the minikube node.

### Connect from your machine

See [readme_postgres_k8s.md](readme_postgres_k8s.md) for the full guide:
where the data lives, DBeaver setup, and troubleshooting. In short:

Forward the Postgres port to localhost and leave it running:

```bash
kubectl port-forward pod/postgres-0 15432:5432
```

Local port `15432` avoids clashing with a Postgres you may already run on
`5432`, for example in Docker or WSL. Then point DBeaver or pgAdmin at
`localhost:15432`, database `salary`, user `salary`, password
`salary-dev-password`. You can also run the app locally against it with the
`postgres` profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=postgres -Dspring-boot.run.arguments="--DB_PORT=15432 --spring.datasource.password=salary-dev-password"
```

In PowerShell, wrap each `-D...` argument in quotes. Without the profile,
`mvn spring-boot:run` still uses the local SQLite file.

### Credentials

The credentials live in the Secret `postgres`. Secret values are only
base64-encoded, not encrypted:

```bash
kubectl get secret postgres -o jsonpath="{.data.POSTGRES_PASSWORD}"
```

Postgres reads `POSTGRES_PASSWORD` **only when it initializes an empty data
directory**. Changing the Secret later does not change the real password.
You would also have to run `ALTER USER` in psql, or wipe the PVC (step 9).

## 7. Deploy a new version

The Deployment uses a fixed tag with `IfNotPresent`, so rebuilding the image
under the same tag does **not** update running pods. Use a new tag each time:

```bash
docker build -t ata-salary-services:1.7 .
```

```bash
minikube image load ata-salary-services:1.7
```

Update the `image:` line in **both** [`k8s/deployment.yaml`](k8s/deployment.yaml)
and [`k8s/seed-job.yaml`](k8s/seed-job.yaml), and the build comment at the
bottom of the `Dockerfile`.

If you changed an entity (for example, added a field or a table), rerun the
seed Job first so it updates the schema. A Job's pod template can't be edited
after creation, so delete the Job and create it again. Existing rows are
kept, because the loaders skip tables that already have data.

```bash
kubectl delete job salary-seed
```

```bash
kubectl apply -f k8s/seed-job.yaml
```

```bash
kubectl wait --for=condition=complete job/salary-seed --timeout=180s
```

Then roll out the app:

```bash
kubectl apply -f k8s/deployment.yaml
```

```bash
kubectl rollout status deployment/salary-app
```

If you skip the Job after an entity change, the new pods fail with
`Schema-validation: missing column` and go into `CrashLoopBackOff`, while the
old pods keep serving. Undo a bad rollout with:

```bash
kubectl rollout undo deployment/salary-app
```

## 8. Everyday commands

| Task | Command |
|------|---------|
| List pods 					| `kubectl get pods -l app=salary-app -o wide` |
| List pods with IMAGES column 	| `kubectl get rs -l app=salary-app -o wide` |
| Logs of one pod 				| `kubectl logs <pod-name>` |
| Follow logs of all pods 		| `kubectl logs -f -l app=salary-app --prefix` |
| Pod details and events 		| `kubectl describe pod <pod-name>` |
| Shell inside a pod 			| `kubectl exec -it <pod-name> -- sh` |
| Scale replicas 				| `kubectl scale deployment/salary-app --replicas=2` |
| Restart all pods 				| `kubectl rollout restart deployment/salary-app` |
| Postgres logs 				| `kubectl logs postgres-0` |
| SQL shell 					| `kubectl exec -it postgres-0 -- psql -U salary -d salary` |
| Seed Job status / logs 		| `kubectl get job salary-seed` / `kubectl logs job/salary-seed` |
| Logs of an init container 	| `kubectl logs <pod-name> -c wait-for-seed` |
| Volumes 						| `kubectl get pvc,pv` |
| Kubernetes dashboard 			| `minikube dashboard` |

`kubectl scale` changes only the live cluster; the next `kubectl apply -f k8s/`
sets it back to the `replicas` value in the YAML.

## 9. Stop and clean up

Remove the app but keep the cluster:

```bash
kubectl delete -f k8s/
```

This **keeps the database**. PVCs created from a StatefulSet's
`volumeClaimTemplates` are not deleted along with the StatefulSet. The data
is still there the next time you run `kubectl apply -f k8s/`, and the seed
Job finds the existing rows and skips the import. To wipe the database too:

```bash
kubectl delete pvc data-postgres-0
```

Stop the cluster (keeps its state, loaded images and the database for next time):

```bash
minikube stop
```

Next time, `minikube start` and `kubectl apply -f k8s/` bring it back; you
only need to rebuild and reload the image if the code changed.

Delete the cluster entirely (removes loaded images **and the database** too):

```bash
minikube delete
```

## Things to know

- **All pods share one Postgres database.** The app connects through the
  `postgres` Spring profile ([`application-postgres.properties`](src/main/resources/application-postgres.properties)),
  which `SPRING_PROFILES_ACTIVE=postgres` turns on. Connection details come
  from the env vars `DB_HOST`, `DB_NAME`, `DB_USERNAME` and `DB_PASSWORD`,
  which are filled from the Secret. Outside Kubernetes the app still defaults
  to SQLite.
- **Postgres is a StatefulSet, not a Deployment.** A StatefulSet gives each
  pod a stable name (`postgres-0`) and its own PVC that follows it across
  restarts. It runs a single replica, because more replicas would be
  separate databases that don't sync with each other. Replication needs an
  operator such as CloudNativePG. In production, most teams use a managed
  database instead.
- **Data survives** pod restarts, redeploys, `kubectl delete -f k8s/` and
  `minikube stop`. It is lost with `kubectl delete pvc data-postgres-0` or
  `minikube delete`.
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
| `salary-app` pods stuck in `Init:0/1` | The `wait-for-seed` init container is waiting for data. Check `kubectl logs <pod-name> -c wait-for-seed`, then `kubectl get job salary-seed` and `kubectl logs job/salary-seed`. |
| `salary-seed` Job `Failed` | Check `kubectl logs job/salary-seed`. Fix the cause, then run `kubectl delete job salary-seed` and `kubectl apply -f k8s/seed-job.yaml`. |
| `postgres-0` and its PVC stuck in `Pending` | No volume was provisioned. Check `kubectl describe pvc data-postgres-0`, and make sure the storage addon is on with `minikube addons enable storage-provisioner`. |
| App logs `Schema-validation: missing column/table` | The database schema is older than the code. Rerun the seed Job (step 7). |
| App logs `password authentication failed` | The Secret changed after the database was created. See *Credentials* in step 6. |
