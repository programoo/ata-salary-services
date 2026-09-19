# Ingress: one entry point for all apps

How to reach `salary-app` and `order-web` through one address, routed by
hostname, instead of a separate NodePort per app. Assumes both apps are
deployed as described in [readme_k8s.md](readme_k8s.md).

## The idea

Without Ingress, every app needs its own port on the node (`30080` for
salary, `30081` for orders). With 10 apps you'd have 10 ports and no readable
URLs.

An **Ingress** is a single front door. It reads the hostname of each request
and forwards it to the matching Service:

```
                          ┌─ Host: salary.localhost ──→ Service salary-app ──→ pods
browser ──→ Ingress ──────┤
           (one entry)    └─ Host: orders.localhost ──→ Service order-web  ──→ pods
```

It has two parts:

| Part                   | What it is                                                             | Here                                                     |
|------------------------|------------------------------------------------------------------------|----------------------------------------------------------|
| **Ingress controller** | A real web server (nginx) running in the cluster that receives traffic | Installed by `minikube addons enable ingress`            |
| **Ingress resource**   | The routing rules, in YAML                                             | [`k8s/ingress.yaml`](k8s/ingress.yaml)                   |

The Ingress resource on its own does nothing: it's just rules, and the
controller follows them. In the cloud, the controller sits behind one real load
balancer with one public IP, shared by all apps.

Typical uses:

- **Host-based routing** (used here): `api.company.com` and `www.company.com` on one IP
- **Path-based routing:** `/api` → backend, `/` → frontend, on one domain
- **HTTPS:** the certificate is configured once at the Ingress, not in every app

## The rules

[`k8s/ingress.yaml`](k8s/ingress.yaml):

```yaml
spec:
  ingressClassName: nginx          # which controller handles it
  rules:
    - host: salary.localhost       # must match the Host header exactly
      http:
        paths:
          - path: /
            pathType: Prefix       # "/" + Prefix = every path
            backend:
              service:
                name: salary-app   # the Service, not the pods
                port:
                  number: 80       # the Service's port, not the container's 8080
    - host: orders.localhost
      ...                          # same, to Service order-web
```

## Set it up

### Step 1: install the ingress controller (once per cluster)

```bash
minikube addons enable ingress
```

Wait until it's running:

```bash
kubectl wait -n ingress-nginx --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s
```

```bash
kubectl get ingressclass
```

```
NAME              CONTROLLER             PARAMETERS   AGE
nginx (default)   k8s.io/ingress-nginx   <none>       24s
```

### Step 2: apply the rules

```bash
kubectl apply -f k8s/ingress.yaml
```

```bash
kubectl get ingress
```

```
NAME       CLASS   HOSTS                               ADDRESS        PORTS   AGE
ata-apps   nginx   salary.localhost,orders.localhost   192.168.49.2   80      1m
```

### Step 3: connect to the controller

With the Docker driver on Windows/macOS, the minikube node isn't reachable from
your PC, so forward a local port to the controller. Keep this terminal open:

```bash
kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 8088:80
```

### Step 4: open the apps

In your browser, including `http://` and the port:

- http://salary.localhost:8088/actuator/health
- http://orders.localhost:8088/

Or with curl:

```bash
curl http://salary.localhost:8088/actuator/health
```

```json
{"status":"UP","groups":["liveness","readiness"]}
```

## How a request travels

```
1. Browser: "where is salary.localhost?"
   → any *.localhost name resolves to 127.0.0.1, built into the browser

2. Connect to 127.0.0.1:8088
   → kubectl port-forward passes it to the ingress controller

3. Request has "Host: salary.localhost"
   → the controller finds the matching rule

4. Rule: Service salary-app, port 80
   → the Service picks one ready pod, the app answers
```

Two things must both be true:

1. **The name must lead to your machine** (step 1).
2. **The Ingress must have a rule for that exact name** (step 3).

The Ingress never sees the IP lookup; it only reads the `Host` header.

In production it's the same chain: step 1 is a real DNS record (for example
`salary.company.com` → the load balancer's IP, often created automatically by
external-dns), and step 2 is a cloud load balancer instead of a port-forward.

## Why `.localhost` names

A browser must turn a name into an IP address before it connects. Made-up names
like `salary-local` or `salary.local` exist in no DNS server, so they don't work
unless you add them to the hosts file, which needs admin rights.

| Option                               | How                                                                 | Needs                  |
|--------------------------------------|---------------------------------------------------------------------|------------------------|
| **`*.localhost`** (used here)        | Chrome, Edge and Firefox send it to `127.0.0.1` automatically       | Nothing                |
| Hosts file                           | Add `127.0.0.1 salary-local` to `C:\Windows\System32\drivers\etc\hosts` | Admin rights           |
| `nip.io`                             | `salary.127.0.0.1.nip.io` is public DNS that resolves to `127.0.0.1` | Internet access        |
| Real DNS record                      | `salary.company.com` → load balancer IP                             | A domain (production)  |

Avoid `.local`: many systems reserve it for local network discovery (mDNS),
which can make lookups slow or unreliable.

`curl` on Windows also resolves `*.localhost` to `127.0.0.1`. If yours doesn't, pass the
host header yourself:

```bash
curl -H "Host: salary.localhost" http://127.0.0.1:8088/actuator/health
```

## Optional: without `:8088`

Instead of the port-forward, run this in an **admin** terminal and keep it open:

```bash
minikube tunnel
```

It serves the Ingress on port 80 of `127.0.0.1`, so http://salary.localhost/actuator/health
works without a port.

## Quick reference

| Task                            | Command                                                                     |
|---------------------------------|-----------------------------------------------------------------------------|
| Install the controller          | `minikube addons enable ingress`                                            |
| Is the controller running?      | `kubectl get pods -n ingress-nginx`                                         |
| Apply / list rules              | `kubectl apply -f k8s/ingress.yaml` / `kubectl get ingress`                 |
| Rule details and backends       | `kubectl describe ingress ata-apps`                                         |
| Connect from your PC            | `kubectl port-forward -n ingress-nginx svc/ingress-nginx-controller 8088:80` |
| Test a host without a browser   | `curl -H "Host: salary.localhost" http://127.0.0.1:8088/actuator/health`    |
| Controller logs (every request) | `kubectl logs -n ingress-nginx deploy/ingress-nginx-controller`             |
| Remove the rules                | `kubectl delete -f k8s/ingress.yaml`                                        |
| Remove the controller           | `minikube addons disable ingress`                                           |

## Troubleshooting

| Symptom                                   | Likely cause and fix                                                                                                                              |
|-------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------|
| **`404 Not Found`** (nginx page)          | The request reached the controller, but **no rule matches the hostname**. Compare the address bar with `HOSTS` in `kubectl get ingress`. Opening `localhost:8088` without a name also gives 404. |
| Page doesn't load at all                  | The connection failed before the Ingress. The port-forward stopped (start it again), or the name doesn't resolve (use a `.localhost` name or a hosts entry). |
| Browser searches instead of opening       | Type the full URL with `http://`.                                                                                                                 |
| `503 Service Temporarily Unavailable`     | The rule matched, but the Service has no ready pods. `kubectl get pods -l app=salary-app` and see [readme_probes_k8s.md](readme_probes_k8s.md).   |
| `kubectl get ingress` shows no `ADDRESS`  | The controller isn't running or `ingressClassName` is wrong. `kubectl get pods -n ingress-nginx` and `kubectl get ingressclass`.                  |
| Port-forward stops after a while or on PC restart | Expected: it only lives as long as its terminal. Start it again.                                                                           |
