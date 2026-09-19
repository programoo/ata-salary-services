# ConfigMap exercise: app settings on Kubernetes

How `salary-app` gets its non-secret settings from a ConfigMap, and what
happens when you change one. Assumes the app is deployed as described in
[readme_k8s.md](readme_k8s.md).

## ConfigMap vs Secret

Both hold key/value settings outside the image. The difference is what goes
in them:

|                             | ConfigMap `salary-app-config`                              | Secret `postgres`                                                               |
|-----------------------------|------------------------------------------------------------|---------------------------------------------------------------------------------|
| File                        | [`k8s/salary-app-config.yaml`](k8s/salary-app-config.yaml) | [`k8s/postgres.yaml`](k8s/postgres.yaml)                                        |
| Holds                       | Profile, DB host/port, CORS origins                        | DB name, user, password                                                         |
| Stored as                   | Plain text                                                 | base64 (not encrypted, but kept separate so access can be restricted with RBAC) |
| Shown by `kubectl describe` | Values shown                                               | Values hidden                                                                   |

Rule of thumb: if leaking the value would be a security problem, it goes in
a Secret. Everything else goes in a ConfigMap.

## What's in the ConfigMap

```yaml
data:
  SPRING_PROFILES_ACTIVE: postgres
  DB_HOST: postgres
  DB_PORT: "5432"                       # values are always strings, so quote numbers
  APP_CORS_ALLOWED_ORIGINS: http://localhost:5173
```

Spring Boot maps env vars to properties automatically:
`APP_CORS_ALLOWED_ORIGINS` becomes `app.cors.allowed-origins`, and `DB_HOST`
fills `${DB_HOST}` in
[`application-postgres.properties`](src/main/resources/application-postgres.properties).

## Three ways a pod reads it

All three are used in this project:

**1. `envFrom`: every key becomes an env var** (app container in
[`k8s/deployment.yaml`](k8s/deployment.yaml), seed container in
[`k8s/seed-job.yaml`](k8s/seed-job.yaml)):

```yaml
envFrom:
  - configMapRef:
      name: salary-app-config
```

**2. `configMapKeyRef`: one key, under a different name** (the
`wait-for-seed` and `wait-for-postgres` init containers, where `psql` expects
`PGHOST`):

```yaml
env:
  - name: PGHOST
    valueFrom:
      configMapKeyRef:
        name: salary-app-config
        key: DB_HOST
```

**3. `env` with `value`: settings for just one workload.** These stay inline
because they differ between the app and the Job:

| Setting                            | App pods   | Seed Job |
|------------------------------------|------------|----------|
| `SPRING_JPA_HIBERNATE_DDL_AUTO`    | `validate` | `update` |
| `APP_DATA_IMPORT_ENABLED`          | `false`    | `true`   |
| `SPRING_MAIN_WEB_APPLICATION_TYPE` | (not set)  | `none`   |

If the same name appears in both `envFrom` and `env`, the `env` entry wins.

There is a fourth way, not used here: mounting the ConfigMap as a
**volume**, where each key becomes a file. See
[Env vars vs mounted files](#env-vars-vs-mounted-files).

## Look at it

```bash
kubectl get configmap salary-app-config -o yaml
```

```bash
kubectl describe configmap salary-app-config
```

The env vars a running pod actually has (Git Bash; in PowerShell use
`findstr`):

```bash
kubectl exec deploy/salary-app -c salary-app -- env | grep -E "DB_|SPRING_|APP_" | sort
```

```
APP_CORS_ALLOWED_ORIGINS=http://localhost:5173
APP_DATA_IMPORT_ENABLED=false
DB_HOST=postgres
DB_NAME=salary
DB_PASSWORD=salary-dev-password
DB_PORT=5432
DB_USERNAME=salary
SPRING_JPA_HIBERNATE_DDL_AUTO=validate
SPRING_PROFILES_ACTIVE=postgres
```

These come from three places:

- **ConfigMap:** `APP_CORS_ALLOWED_ORIGINS`, `DB_HOST`, `DB_PORT`, `SPRING_PROFILES_ACTIVE`
- **Secret:** `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`
- **Inline `env`:** `APP_DATA_IMPORT_ENABLED`, `SPRING_JPA_HIBERNATE_DDL_AUTO`

The pod can't tell which source a value came from. To the app they're all
just env vars.

## Exercise: change a value and watch when it takes effect

Goal: let a web app on `http://localhost:3000` call the API, by adding it to
`APP_CORS_ALLOWED_ORIGINS`.

Use two terminals. In **terminal 1**, start a tunnel and leave it running:

```bash
kubectl port-forward service/salary-app 8080:80
```

In **terminal 2**, run the steps below.

### Step 1: the origin is rejected

Send the CORS "preflight" request a browser would send:

```bash
curl -i -X OPTIONS http://localhost:8080/api/orders -H "Origin: http://localhost:3000" -H "Access-Control-Request-Method: GET"
```

Result: `HTTP/1.1 403` and `Invalid CORS request`.

In PowerShell, use `curl.exe` instead of `curl`.

### Step 2: change the ConfigMap

Edit [`k8s/salary-app-config.yaml`](k8s/salary-app-config.yaml):

```yaml
  APP_CORS_ALLOWED_ORIGINS: http://localhost:5173,http://localhost:3000
```

Preview what will change, then apply:

```bash
kubectl diff -f k8s/salary-app-config.yaml
```

```bash
kubectl apply -f k8s/salary-app-config.yaml
```

### Step 3: the running pods still use the old value

```bash
kubectl get configmap salary-app-config -o jsonpath="{.data.APP_CORS_ALLOWED_ORIGINS}"
```

```bash
kubectl exec deploy/salary-app -c salary-app -- printenv APP_CORS_ALLOWED_ORIGINS
```

The ConfigMap shows both origins, but the pod still prints only
`http://localhost:5173`. Run the `curl` from step 1 again: still `403`.

**This is the key lesson.** Env vars are copied into a container once, when
it starts. Changing the ConfigMap doesn't touch running pods, and it doesn't
trigger a rollout either, because the Deployment's own spec didn't change.

### Step 4: restart the pods

```bash
kubectl rollout restart deployment/salary-app
```

```bash
kubectl rollout status deployment/salary-app
```

`rollout restart` adds a timestamp annotation to the pod template. That
counts as a change, so the Deployment does a normal rolling update, and the
new pods read the ConfigMap as it is now.

The port-forward in terminal 1 stops, because its pod was replaced. Start it
again.

### Step 5: the origin is allowed

```bash
kubectl exec deploy/salary-app -c salary-app -- printenv APP_CORS_ALLOWED_ORIGINS
```

This now prints `http://localhost:5173,http://localhost:3000`. Run the
`curl` from step 1 again: `HTTP/1.1 200` with
`Access-Control-Allow-Origin: http://localhost:3000`.

### Step 6: clean up

Undo the edit in `k8s/salary-app-config.yaml` (or keep it if you actually
need that origin), then run:

```bash
kubectl apply -f k8s/salary-app-config.yaml
```

```bash
kubectl rollout restart deployment/salary-app
```

While the old pods shut down, one may briefly show `Error`. That's normal:
the JVM exits with code 143 when Kubernetes stops it.

## Pitfall: changing the cluster instead of the file

You can change the live ConfigMap directly:

```bash
kubectl edit configmap salary-app-config
```

It works, but now the cluster no longer matches git, and the next
`kubectl apply -f k8s/` silently puts the file's value back. To find this
kind of drift:

```bash
kubectl diff -f k8s/
```

No output (exit code 0) means the cluster matches the files. A `-`/`+` pair
means the live value differs:

```
-  APP_CORS_ALLOWED_ORIGINS: http://localhost:5173,http://localhost:3000
+  APP_CORS_ALLOWED_ORIGINS: http://localhost:5173
```

Prefer editing the YAML and applying it, so git stays the source of truth.

## Env vars vs mounted files

|                  | Env vars (`envFrom`, `configMapKeyRef`) | Volume mount (each key becomes a file)               |
|------------------|-----------------------------------------|------------------------------------------------------|
| Picks up changes | Only after a pod restart                | Files update in the running pod after about a minute |
| App must...      | Nothing special                         | Re-read the file, or be restarted anyway             |
| Good for         | Simple settings like this app's         | Whole config files (`nginx.conf`, `logback.xml`)     |

Spring Boot reads its settings only at startup, so a mount would still need a
restart. That's why this project uses env vars.

Tools such as Reloader, or a Kustomize `configMapGenerator` (which adds a
content hash to the ConfigMap name), trigger the restart automatically when
the config changes.

## Quick reference

| Task                             | Command                                                                             |
|----------------------------------|-------------------------------------------------------------------------------------|
| List ConfigMaps                  | `kubectl get configmaps`                                                            |
| Show values                      | `kubectl get configmap salary-app-config -o yaml`                                   |
| Create one from the command line | `kubectl create configmap demo --from-literal=KEY=value`                            |
| Create one from a file           | `kubectl create configmap demo --from-file=application.properties`                  |
| Preview changes                  | `kubectl diff -f k8s/salary-app-config.yaml`                                        |
| Apply changes                    | `kubectl apply -f k8s/salary-app-config.yaml`                                       |
| Make pods use the new values     | `kubectl rollout restart deployment/salary-app`                                     |
| Check a value inside a pod       | `kubectl exec deploy/salary-app -c salary-app -- printenv APP_CORS_ALLOWED_ORIGINS` |

## Troubleshooting

| Symptom                                                                                         | Likely cause and fix                                                                                                                               |
|-------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------|
| Pod stuck in `CreateContainerConfigError`                                                       | The ConfigMap, or a key named in `configMapKeyRef`, doesn't exist. `kubectl describe pod <pod-name>` names it. Apply `k8s/salary-app-config.yaml`. |
| Changed a value but the app still uses the old one                                              | Pods weren't restarted. Run `kubectl rollout restart deployment/salary-app`.                                                                       |
| Your change disappeared                                                                         | Someone ran `kubectl apply -f k8s/` with the old file. Put the change in the YAML.                                                                 |
| `apply` fails with `cannot unmarshal number into Go struct field ConfigMap.data of type string` | A number or `true`/`false` wasn't quoted. Write `DB_PORT: "5432"`.                                                                                 |
