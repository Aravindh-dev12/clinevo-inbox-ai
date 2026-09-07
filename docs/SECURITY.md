# Security and operational controls

The candidate stack has two explicit authentication modes so the assignment remains easy to run while the production boundary is not disguised as a demo API key.

## Demo mode

`AUTH_MODE=demo` is the default for local evaluation. Read endpoints are open on the isolated developer machine. If `CLINEVO_API_KEY` is configured, mutation requests require `X-API-Key`. The Angular UI can supply the configured local key through its existing runtime configuration. This mode is **not** an enterprise identity solution.

## OIDC/JWT production mode

Set:

```text
AUTH_MODE=oidc
OIDC_ISSUER_URI=https://idp.example/...
OIDC_JWK_SET_URI=https://idp.example/.../jwks
OIDC_AUDIENCE=clinevo-inbox
OIDC_ROLES_CLAIM=roles
OIDC_PRINCIPAL_CLAIM=preferred_username
```

The Spring API becomes a stateless OAuth2 Resource Server. `/api/health` and the Actuator health probe stay public for orchestration. Inbox/read/write/literature APIs require `ROLE_REVIEWER` or `ROLE_ADMIN`; Actuator info/metrics require `ROLE_ADMIN`. JWT signature, issuer and audience are validated. Roles are mapped from a configurable JWT claim.

In OIDC mode the reviewer recorded in the audit trail comes from the authenticated JWT principal. A client cannot spoof a different reviewer by changing the JSON request body.

For a browser deployment, integrate the Angular application with the organization's approved OIDC Authorization Code + PKCE client or place it behind an approved BFF/access proxy. Do not place long-lived bearer tokens in source code, static runtime configuration, localStorage, or the repository.

## Correlation and errors

Every HTTP request receives an `X-Request-ID`. A safe caller-supplied ID is preserved; malformed IDs are replaced. The ID is added to MDC, returned in API/security error bodies, and attached to human-review audit details. Logs must not contain message bodies, evidence snippets, attachment bytes, credentials, tokens or model prompts containing document content.

## Metrics

Actuator provides HTTP server/client timers, including observed Spring `RestClient` calls to the AI service. Additional low-cardinality application metrics include durable queue depth, processing/failed counts, stored message count, persisted retry count, processing duration snapshot, reviewer action counts and reviewer API duration. No patient-like content, file name, subject, reporter, reviewer identity, prompt text or evidence is used as a metric tag.

## HTTP controls

- narrow configured CORS origin list
- stateless server-side security context
- anti-framing and no-referrer headers
- `nosniff`
- restrictive API CSP
- UI Nginx CSP, permissions policy, anti-framing and referrer policy
- request/upload size limits
- PDF magic-signature validation before parsing
- environment-only credentials

## Production deployment requirements beyond this repository

Use TLS end to end, preferably mTLS for service-to-service calls; a managed secrets system; regular key rotation; WAF/rate limits; malware scanning before parsing; encrypted immutable original-object storage; formal retention/legal-hold rules; SIEM export; OpenTelemetry traces; centralized dashboards/alerts; dependency/image scanning; backup/restore exercises; least-privilege Oracle accounts; IdP access reviews; and the validation/change-control evidence appropriate to the organization's regulated environment.
