# SPIFFE/SPIRE Zero Trust Identity Lab – Plan

Working document. Not rendered by Antora.

## Intake

- **Audience:** Platform engineers, application developers, security architects
- **Customer question:** How do we implement zero-trust workload identity across microservices and VM-hosted databases on OpenShift, using SPIFFE/SPIRE without managing certificates manually?
- **Products and pinned versions:**
  - Red Hat Zero Trust Workload Identity Manager Operator (GA, OperatorHub `redhat-operators`)
  - OpenShift Container Platform (RHDP-provisioned, version TBD after cluster verification)
  - OpenShift Virtualization (for PostgreSQL VM in Module 5)
  - SPIRE Server, SPIRE Agent, SPIFFE CSI Driver (deployed by Operator)
  - Pinned versions: TBD after live cluster verification
- **Tracks and time budget:** Single track, ~4 hours
- **Where:** RHDP order, shared OCP cluster; GitOps-built stack (SPIRE Operator + prereqs pre-deployed)
- **Tenancy:** Shared cluster, per-user namespaces derived as `$(oc whoami)-spire-lab`
- **Participant identity:** Their own OpenShift user (terminal OFF, `showroom.terminal: false`); Web Terminal Operator pre-installed via GitOps
- **Test cluster:** 3 master + 3 worker nodes, 8 CPU / 16 GB RAM per node, shared by up to 10 participants
- **Git repo:** https://github.com/ryanzhang/spiffe-spire-showroom-lab.git

## Lab map

| Module | Outcome | Time |
|---|---|---|
| Overview | Learner can explain SPIFFE ID, SVID, trust bundle and the zero-trust model | 20 min |
| 1. Exploring the SPIRE Deployment | Observe SPIRE Server, Agent, CSI driver health; read trust bundle | 30 min |
| 2. Workload Registration and X.509 SVID | Register a workload, mount SVID via CSI, verify certificate fields | 45 min |
| 3. mTLS Microservice Communication | Prove Service A ↔ Service B allowed; Service C → Service B denied | 45 min |
| 4. JWT-SVID REST API Authentication | Caller presents JWT-SVID; REST API validates via SPIRE OIDC Discovery endpoint | 45 min |
| 5. SPIRE Agent on VM – PostgreSQL mTLS | SPIRE Agent on KubeVirt VM, mTLS between OCP microservice and PostgreSQL on VM | 60 min |
| Cleanup | Participant namespace teardown | 15 min |

**Total: ~4h 20 min (fits 4h with facilitator pacing)**

## Design decisions

- SPIRE Operator namespace: `zero-trust-workload-identity-manager`
- Trust domain: `cluster.local` (default; verify after cluster is up)
- Per-user namespace: `<oc whoami>-spire-lab` (namespace derivation on 02-details.adoc pre-flight)
- Decoy namespace: `peer-demo-spire-lab` (GitOps-deployed, for Module 3 cross-user denial)
- PostgreSQL VM: KubeVirt VM, join-token attestation, one shared VM with per-user databases
- Module 3 mTLS approach: SPIFFE CSI Driver + workload-level cert loading (no Envoy sidecar for simplicity); TBD during verification
- Module 5 VM attestation: join_token (simplest for lab context)

## Exercises

### Module 1, Exercise 1: Verify SPIRE Operator deployment
- **Teaches:** How the Zero Trust Workload Identity Manager Operator provisions SPIRE components
- **Draft steps:** `oc get ZeroTrustWorkloadIdentityManager -A`, `oc get pods -n zero-trust-workload-identity-manager`, observe Server/Agent/CSI driver pods
- **Negative control:** Before workload registration, a pod cannot fetch an SVID (no SpiffeClusterSPIFFEID CR exists yet)
- **Caveats:** // UNVERIFIED

### Module 1, Exercise 2: Read the trust bundle
- **Teaches:** Trust bundle as the foundation of SPIFFE federation
- **Draft steps:** `oc get configmap -n zero-trust-workload-identity-manager spire-bundle -o jsonpath='{.data.bundle\.crt}'` | openssl x509 -text -noout
- **Caveats:** // UNVERIFIED – ConfigMap name may differ; verify on cluster

### Module 2, Exercise 1: Create a SpiffeClusterSPIFFEID registration entry
- **Teaches:** Workload attestation via Kubernetes pod selector
- **Draft steps:** Apply SpiffeClusterSPIFFEID CR selecting the user's microservice pod in their namespace
- **Negative control:** Pod without matching selector cannot access SVID volume (empty mount)
- **Caveats:** // UNVERIFIED

### Module 2, Exercise 2: Inspect X.509 SVID
- **Teaches:** SVID structure – SPIFFE URI SAN, TTL, issuing CA chain
- **Draft steps:** `oc exec` into pod, `openssl x509 -in /run/spiffe/tls.crt -text -noout | grep -A2 "Subject Alternative Name"`
- **Caveats:** // UNVERIFIED – CSI mount path TBD

### Module 3, Exercise 1: mTLS between microservices
- **Teaches:** Mutual TLS using SVID certs; positive and negative path
- **Draft steps:** curl service-b from service-a pod (positive); curl from peer-demo pod (negative – TLS handshake failure)
- **Negative control:** peer-demo pod denied at TLS layer; specific error captured
- **Caveats:** // UNVERIFIED – mTLS enforcement mechanism TBD (CSI + app-level or Envoy sidecar)

### Module 4, Exercise 1: Obtain and present JWT-SVID
- **Teaches:** JWT-SVID as a short-lived bearer token for REST API auth
- **Draft steps:** Fetch JWT-SVID via SPIFFE Workload API; call REST API; API validates via SPIRE OIDC Discovery endpoint
- **Negative control:** Request without JWT or with expired token rejected (HTTP 401)
- **Caveats:** // UNVERIFIED

### Module 5, Exercise 1: Observe SPIRE Agent on KubeVirt VM
- **Teaches:** Extending SPIFFE/SPIRE trust to non-Kubernetes workloads
- **Draft steps:** `spire-server agent list` (shows VM agent); SSH into VM, observe SPIRE Agent running
- **Caveats:** // UNVERIFIED – join_token provisioned by GitOps

### Module 5, Exercise 2: PostgreSQL mTLS with SVID
- **Teaches:** Application modernisation: SPIFFE identity for DB connections
- **Draft steps:** Connect to PostgreSQL using SVID cert; verify `SELECT * FROM pg_stat_ssl` shows ssl=on; attempt connection without cert (denied by pg_hba.conf `hostssl ... cert`)
- **Negative control:** Connection without valid SVID cert fails
- **Caveats:** // UNVERIFIED – PostgreSQL ssl_ca_file = SPIRE trust bundle CA
