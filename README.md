# Keycloak → Nimbus OIDC → OpenSearch (Java 8)

Dieses Demo zeigt Authentifizierung **und** Autorisierung ohne Spring Boot und
ohne Spring Security. Die Anwendung ist eine reine Java-8-Anwendung mit
`com.sun.net.httpserver.HttpServer`; für OAuth 2.0 / OpenID Connect wird Nimbus
`oauth2-oidc-sdk` verwendet.

## Ziel der Demo

Der Benutzer `demo` kann sich immer über Keycloak anmelden. Lesen aus OpenSearch
darf er aber nur, solange Keycloak ihm die Realm-Rolle `os_allow_read` gibt.

```text
Keycloak Realm Role       OpenSearch backend role      OpenSearch role
os_allow_read       --->  os_allow_read          --->  demo_reader
                                                        |
                                                        +-- read auf demo-data
```

Damit lässt sich der Unterschied zwischen **Authentifizierung** und
**Autorisierung** direkt beobachten.

## Start

```bash
docker compose up --build
```

Danach:

- App: http://localhost:8081
- Keycloak: http://keycloak.localhost:8080
- OpenSearch: http://localhost:9200

### Demo-Login

- Benutzer: `demo`
- Passwort: `demo`

Keycloak-Admin:

- Benutzer: `admin`
- Passwort: `admin`

## Autorisierungs-Demo

### Ausgangszustand: Lesen erlaubt

Der importierte Benutzer `demo` besitzt in Keycloak ausschließlich die
Realm-Rolle:

```text
os_allow_read
```

Der Realm-Role-Mapper schreibt sie in den Access Token:

```json
{
  "preferred_username": "demo",
  "roles": ["os_allow_read"],
  "aud": ["opensearch"]
}
```

OpenSearch liest `roles` als Backend-Rollen. Beim Start erzeugt
`opensearch-init` über die Security REST API die eingeschränkte Rolle
`demo_reader`:

```json
{
  "cluster_permissions": [],
  "index_permissions": [
    {
      "index_patterns": ["demo-data"],
      "allowed_actions": ["read"]
    }
  ],
  "tenant_permissions": []
}
```

und mappt:

```text
os_allow_read -> demo_reader
```

`/search` funktioniert daher zunächst. `/whoami` sollte unter anderem
`os_allow_read` als Backend-Rolle und `demo_reader` als OpenSearch-Rolle zeigen.

### Rolle in Keycloak entziehen

1. Öffne http://keycloak.localhost:8080/admin/ und melde dich mit `admin/admin` an.
2. Wähle den Realm `demo`.
3. Öffne `Users` → `demo` → `Role mapping`.
4. Entferne die Realm-Rolle `os_allow_read`.
5. Gehe in der Demo-App auf `/refresh`, damit sofort ein neuer Access Token
   ausgestellt wird. Alternativ maximal ca. 60 Sekunden warten.
6. Prüfe `/token-info`: `os_allow_read` darf im neuen Access Token nicht mehr
   enthalten sein.
7. Rufe `/whoami` auf: OpenSearch kennt den Benutzer weiterhin, aber
   `demo_reader` fehlt.
8. Rufe `/search` auf: OpenSearch antwortet jetzt mit **HTTP 403 Forbidden**.

Das ist bewusst **kein 401**: Der Access Token ist weiterhin gültig und der
Benutzer wurde erfolgreich authentifiziert. Ihm fehlt lediglich die benötigte
Berechtigung für `demo-data`.

### Rolle wieder vergeben

Vergib `os_allow_read` in Keycloak wieder, rufe `/refresh` auf und danach erneut
`/search`. Die Suche funktioniert wieder.

## Warum ein bereits ausgestellter Token noch funktioniert

Keycloak ändert bereits signierte JWTs nicht nachträglich. Wenn der aktuelle
Access Token noch

```json
"roles": ["os_allow_read"]
```

enthält, darf OpenSearch bis zum Ablauf dieses Tokens weiter lesen. Das Demo setzt
die Access-Token-Lebensdauer deshalb bewusst auf 60 Sekunden. `/refresh` macht die
Änderung unmittelbar sichtbar.

## Interessante Endpunkte

- `/login` – startet den Authorization-Code-Flow
- `/callback` – verarbeitet `code` + `state` und holt Tokens
- `/search` – liest `demo-data` mit dem Keycloak Access Token; zeigt bei fehlendem Recht explizit 403
- `/whoami` – ruft `/_plugins/_security/authinfo` auf
- `/token-info` – zeigt JWT-Claims und für die Demo auch rohe Tokens
- `/refresh` – erzwingt einen Refresh über `RefreshTokenGrant`

## Nimbus-Abhängigkeit

```xml
<dependency>
  <groupId>com.nimbusds</groupId>
  <artifactId>oauth2-oidc-sdk</artifactId>
  <version>11.38.1</version>
  <classifier>jdk8</classifier>
</dependency>
```

## Token an OpenSearch weitergeben

In `OpenSearchService` bleibt die relevante Zeile sichtbar:

```java
connection.setRequestProperty(
        "Authorization",
        accessToken.toAuthorizationHeader());
```

Nimbus erzeugt daraus:

```text
Authorization: Bearer eyJ...
```

OpenSearch validiert den Token und verwendet den Claim `roles` als
`backend_roles`. Die konkrete Indexberechtigung kommt erst über das
OpenSearch-Role-Mapping zustande.

## Docker-intern vs. Browser

- Browser/Public Keycloak URL: `http://keycloak.localhost:8080`
- Docker-Service-Name für Readiness: `http://keycloak:8080`

`KC_HOSTNAME` bleibt auf der öffentlichen URL, damit Keycloak im Browser keine
Docker-internen `keycloak:8080`-URLs erzeugt.

## Bewusste Demo-Vereinfachungen

- HTTP statt HTTPS zwischen den Containern
- Tokens werden in `/token-info` angezeigt
- Session-Speicher nur im RAM
- kein Keycloak-End-Session-Logout; `/logout` löscht nur die lokale Session
- `all_access` darf im Demo-Cluster über die Security REST API die Bootstrap-Rolle
  `demo_reader` anlegen; der Demo-Benutzer selbst erhält dieses Recht nicht

Für Produktion wären TLS, persistente Session-/Keycloak-Daten, restriktive
Bootstrap-Rechte und ein vollständiges Logout-Konzept zu ergänzen.
