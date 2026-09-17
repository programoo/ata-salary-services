# Accessing the Postgres database on Kubernetes (minikube)

How to find and connect to the database that `salary-app` uses when it runs
on minikube. For deploying it in the first place, see
[readme_k8s.md](readme_k8s.md).

## Where the database is

Postgres runs as the pod `postgres-0`, created by the StatefulSet in
[`k8s/postgres.yaml`](k8s/postgres.yaml). Its data files are on a
PersistentVolume inside the minikube node, not in a Windows folder:

```
Windows
 └─ Docker Desktop
     └─ "minikube" container (the Kubernetes node)
         └─ /tmp/hostpath-provisioner/default/data-postgres-0/pgdata   <- Postgres data files
             ^ mounted into pod postgres-0 at /var/lib/postgresql/data
```

The chain that connects them:

| Object | Name | Role |
|--------|------|------|
| PersistentVolumeClaim | `data-postgres-0` | The pod's request for 1Gi of storage |
| PersistentVolume | `pvc-...` (generated) | The storage minikube's `standard` StorageClass created for the claim |
| hostPath on the node | `/tmp/hostpath-provisioner/default/data-postgres-0` | Where the files actually live |

Check it yourself:

```bash
kubectl get pvc data-postgres-0
```

```bash
kubectl get pv
```

```bash
minikube ssh -- "sudo ls /tmp/hostpath-provisioner/default/data-postgres-0/pgdata"
```

Don't edit these files directly. Always go through Postgres.

## How the app connects to the database

The Deployment tells the app **where and how** to connect, but it doesn't
make the connection itself. Five pieces work together:

```
 ConfigMap "salary-app-config"
 ─────────────────────────────
 SPRING_PROFILES_ACTIVE ──┐
 DB_HOST = postgres     ──┤
 DB_PORT = 5432         ──┤ envFrom
                          ▼
 Secret "postgres"            Deployment "salary-app"              Service "postgres"          Pod postgres-0
 ─────────────────            ───────────────────────              ──────────────────          ──────────────
 POSTGRES_DB       ──────►    env DB_NAME                          DNS name "postgres"  ────►  Postgres on :5432
 POSTGRES_USER     ──────►    env DB_USERNAME                      → pod IP of postgres-0
 POSTGRES_PASSWORD ──────►    env DB_PASSWORD                              ▲
                              env DB_HOST = postgres  ─────────────────────┘
                              env SPRING_PROFILES_ACTIVE = postgres
                                        │
                                        ▼
                              Spring Boot app in each pod builds
                              jdbc:postgresql://postgres:5432/salary
                              and connects with its connection pool (HikariCP)
```

| Piece | What it does | What it doesn't do |
|-------|--------------|--------------------|
| **ConfigMap** `salary-app-config` ([`k8s/salary-app-config.yaml`](k8s/salary-app-config.yaml)) | Stores the non-secret settings: the `postgres` profile, `DB_HOST`, `DB_PORT`, CORS origins. See [readme_configmap_k8s.md](readme_configmap_k8s.md). | Update running pods when changed (restart them) |
| **Secret** `postgres` ([`k8s/postgres.yaml`](k8s/postgres.yaml)) | Stores the database name, user and password once. Both Postgres and the app read them from here. | Encrypt anything (values are only base64-encoded) |
| **Deployment** `salary-app` ([`k8s/deployment.yaml`](k8s/deployment.yaml)) | Loads the ConfigMap and Secret values into env vars, and waits for data before starting the app | Open or manage connections |
| **Service** `postgres` ([`k8s/postgres.yaml`](k8s/postgres.yaml)) | Gives the database a stable DNS name, `postgres`, that points to whatever IP `postgres-0` currently has | Load balance: it's headless (`clusterIP: None`), so the name resolves straight to the pod IP |
| **The app** ([`application-postgres.properties`](src/main/resources/application-postgres.properties)) | Builds the JDBC URL from the env vars, opens the connections, and reconnects if Postgres restarts | Know it's running in Kubernetes: it only sees env vars and a hostname |

The **Service** is what makes this survive restarts. When `postgres-0` is
recreated it usually gets a new IP. The name `postgres` then points to the
new IP, and the app reconnects without any change to the Deployment.

### See it in your cluster

The commands below pipe to `grep`, so run them in Git Bash. In PowerShell,
use `findstr` instead.

**1. The env vars the Deployment injected** into an app pod:

```bash
kubectl exec deploy/salary-app -c salary-app -- env | grep -E "DB_|SPRING_PROFILES"
```

```
DB_PASSWORD=salary-dev-password
SPRING_PROFILES_ACTIVE=postgres
DB_HOST=postgres
DB_NAME=salary
DB_USERNAME=salary
```

**2. What the name `postgres` resolves to** from inside an app pod:

```bash
kubectl exec deploy/salary-app -c salary-app -- getent hosts postgres
```

```
10.244.0.24     postgres.default.svc.cluster.local
```

The short name `postgres` works because the pod's DNS search list includes
`default.svc.cluster.local`, the pod's own namespace.

**3. The pod IP behind the Service.** It matches the IP above:

```bash
kubectl get endpointslices -l kubernetes.io/service-name=postgres
```

```
NAME             ADDRESSTYPE   PORTS   ENDPOINTS     AGE
postgres-qsdst   IPv4          5432    10.244.0.24   19m
```

**4. Who is connected to Postgres**, grouped by client IP:

```bash
kubectl exec postgres-0 -- psql -U salary -d salary -c "SELECT client_addr, count(*) FROM pg_stat_activity WHERE usename='salary' GROUP BY client_addr;"
```

```
 client_addr | count
-------------+-------
 10.244.0.20 |    10
 10.244.0.21 |    10
 10.244.0.23 |    10
 10.244.0.25 |    10
 10.244.0.26 |    10
 127.0.0.1   |     2
             |     2
```

Each of the 5 app pods holds 10 connections, HikariCP's default pool size.
Compare the IPs with
`kubectl get pods -l app=salary-app -o wide`. The last two rows don't come
from app pods:

- `127.0.0.1`: connections through `kubectl port-forward`, such as DBeaver.
  The tunnel delivers them from inside the Postgres pod.
- blank: local socket connections inside the pod, such as the `psql` you just
  ran with `kubectl exec`.

Your IPs and names will differ. Try deleting `postgres-0` and running
steps 2 to 4 again: the IP changes, the name `postgres` follows it, and the
app pods reconnect.

## Connect with DBeaver

The pod isn't reachable from Windows on its own, so you open a tunnel to it
with `kubectl port-forward`.

### 1. Start the tunnel

Run this in a terminal and **leave it running** while you use DBeaver:

```bash
kubectl port-forward pod/postgres-0 15432:5432
```

It prints `Forwarding from 127.0.0.1:15432 -> 5432`. Stop it with Ctrl+C.

Use local port **15432**, not 5432. On this machine, `localhost:5432` is
already used by something running in WSL (`wslrelay.exe`). If you use 5432,
DBeaver would connect to that database instead of the one in minikube. To see
what is using a port:

```bash
netstat -ano | findstr :5432
```

### 2. Create the connection

In DBeaver: **Database → New Database Connection → PostgreSQL**, then:

| Field | Value |
|-------|-------|
| Host | `localhost` |
| Port | `15432` |
| Database | `salary` |
| Username | `salary` |
| Password | `salary-dev-password` |

DBeaver may ask to download the PostgreSQL driver the first time. Click
**Test Connection**, then **Finish**.

The tables are under **salary → Schemas → public → Tables**:

| Table | Contents |
|-------|----------|
| `salary_records` | 3,777 salary survey rows |
| `orders` | 123 orders |
| `order_warnings` | Warning messages for each order |

### Good to know

- **The tunnel has to be running.** If you close the terminal, restart your
  machine, or `postgres-0` restarts, DBeaver loses the connection. Run the
  `port-forward` command again and reconnect.
- **Changes are live.** All 5 `salary-app` pods share this database, so
  anything you insert, update or delete in DBeaver shows up in the API
  straight away.
- **Don't change the tables' structure by hand.** The app pods start with
  `ddl-auto=validate` and fail if a table or column they expect is missing.
  Change the entities and rerun the seed Job instead (step 7 in
  [readme_k8s.md](readme_k8s.md)).
- **Where the credentials come from.** The database name, user and password
  are in the Secret `postgres` in [`k8s/postgres.yaml`](k8s/postgres.yaml).
  Read the password back from the cluster with:

  ```bash
  kubectl get secret postgres -o jsonpath="{.data.POSTGRES_PASSWORD}"
  ```

  The value is base64-encoded. These are local-development credentials only.

## Quick access without DBeaver

Open `psql` inside the pod. No tunnel is needed:

```bash
kubectl exec -it postgres-0 -- psql -U salary -d salary
```

Useful commands: `\dt` lists the tables, `\d orders` describes a table,
`SELECT count(*) FROM salary_records;` runs a query, and `\q` quits.

## Run the app locally against this database

With the tunnel running, start the app on your machine using the `postgres`
profile:

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=postgres -Dspring-boot.run.arguments="--DB_PORT=15432 --spring.datasource.password=salary-dev-password"
```

In PowerShell, wrap each `-D...` argument in quotes.

## When is the data lost?

| Action | Data kept? |
|--------|-----------|
| Restart or delete pod `postgres-0` | Yes |
| `kubectl rollout restart` / deploy a new app version | Yes |
| `kubectl delete -f k8s/` | Yes (the PVC is not deleted) |
| `minikube stop` then `minikube start` | Yes |
| `kubectl delete pvc data-postgres-0` | **No** |
| `minikube delete` | **No** |

## Troubleshooting

| Symptom | Likely cause and fix |
|---------|----------------------|
| DBeaver: `Connection refused` | The port-forward isn't running. Start it again (step 1). |
| DBeaver: `password authentication failed for user "salary"` | You're on port 5432 and reaching a different Postgres. Use port `15432`. Otherwise, check the password in the Secret. |
| DBeaver connects but tables are missing or empty | Wrong database or port. Check the database is `salary` and the port is `15432`. |
| `port-forward` fails with `address already in use` | Something else is using 15432. Pick another local port, e.g. `25432:5432`, and use it in DBeaver. |
| `port-forward` exits with `lost connection to pod` | `postgres-0` restarted. Wait for `kubectl get pod postgres-0` to show `1/1 Running`, then start the tunnel again. |
