# Keycloak → Auth Manager → OpenSearch (Java 8)

Dieses Demo zeigt Authentifizierung und Autorisierung ohne Spring Boot und ohne Spring Security. Die eigentliche Demo-App kommuniziert **nicht mehr direkt mit Keycloak**. Stattdessen übernimmt ein separater `auth-manager` den gesamten OIDC-Flow einschließlich Refresh Token.

## Architektur

```text
Browser
  |
  | /login
  v
Demo-App :8081
  |
  | Redirect mit authId
  v
Auth Manager :8082
  |
  | Authorization Code Flow
  v
Keycloak :8080
  |
  | callback
  v
Auth Manager
  |
  | Access Token + Refresh Token serverseitig pro authId
  |
  +<---------------- Demo-App fragt gültigen Access Token ab
                         |
                         | Authorization: Bearer <access_token>
                         v
                    OpenSearch :9200
```

Die App kennt weder Keycloak-Endpunkte noch Client Secret noch Refresh Token. Nimbus OIDC wird ausschließlich im `auth-manager` verwendet.

## Start

```bash
docker compose up --build
```

Danach:

- App: http://localhost:8081
- Auth Manager: http://localhost:8082
- Keycloak: http://keycloak.localhost:8080
- OpenSearch: http://localhost:9200

Demo-Login: `demo` / `demo`

Keycloak-Admin: `admin` / `admin`

## Ablauf

1. Die App erzeugt pro lokaler Session eine zufällige `authId`.
2. `/login` der App redirectet zu `http://localhost:8082/login?authId=...`.
3. Der Auth Manager erzeugt mit Nimbus `state` und `nonce` und leitet den Browser zu Keycloak weiter.
4. Keycloak ruft nach erfolgreichem Login `http://localhost:8082/callback` auf.
5. Nur der Auth Manager tauscht den Authorization Code gegen Access-, Refresh- und ID-Token.
6. Der Auth Manager validiert das ID Token und speichert die Tokens serverseitig unter der `authId`.
7. Die App fragt vor einem OpenSearch-Aufruf `GET /token?authId=...` beim Auth Manager an.
8. Ist der Access Token fast abgelaufen, refresht der Auth Manager ihn selbstständig.
9. Die App sendet den erhaltenen Access Token als `Authorization: Bearer ...` an OpenSearch.

## Autorisierung

Der Benutzer `demo` besitzt in Keycloak die Realm-Rolle:

```text
os_allow_read
```

Der Access Token enthält diese Rolle im Claim `roles`. OpenSearch mappt:

```text
Keycloak role       OpenSearch backend role    OpenSearch role
os_allow_read  ---> os_allow_read         ---> demo_reader
                                             |
                                             +-- read auf demo-data
```

Entfernst du `os_allow_read` in Keycloak und rufst in der App `/refresh` auf, erzeugt ausschließlich der Auth Manager einen neuen Access Token. Danach liefert `/search` HTTP 403.

## Services

### app

Reines Java 8. Verantwortlich für:

- Benutzeroberfläche der Demo
- lokale Session / `authId`
- Token-Anfrage beim Auth Manager
- Weitergabe des Access Tokens an OpenSearch

Die App hat **keine Nimbus-Abhängigkeit** und keine Keycloak-Konfiguration.

### auth-manager

Reines Java 8 mit Nimbus `oauth2-oidc-sdk`. Verantwortlich für:

- Erzeugen der Keycloak-Login-URL
- `state` und `nonce`
- Callback-Verarbeitung
- Authorization-Code-Exchange
- ID-Token-Validierung
- Speicherung von Access- und Refresh-Token
- automatischen und erzwungenen Refresh
- Bereitstellung eines gültigen Access Tokens für die App

### OpenSearch

Validiert den Keycloak Access Token und mappt `os_allow_read` auf die eingeschränkte Rolle `demo_reader`.

## Wichtige Endpunkte

App:

- `/login` – startet den Login über den Auth Manager
- `/search` – liest `demo-data`
- `/whoami` – zeigt OpenSearch `authinfo`
- `/token-info` – zeigt den vom Auth Manager gelieferten Access Token
- `/refresh` – fordert den Auth Manager zu einem sofortigen Refresh auf

Auth Manager:

- `/login?authId=...&returnUrl=...`
- `/callback`
- `/token?authId=...`
- `/refresh?authId=...`
- `/status?authId=...`

## Docker-intern vs. Browser

Keycloak hat zwei Sichtweisen:

- Browser / Issuer: `http://keycloak.localhost:8080`
- Container-intern: `http://keycloak:8080`

Der Auth Manager nutzt deshalb getrennte öffentliche und interne Keycloak-URLs.

Genauso nutzt die App:

- Browser-URL des Auth Managers: `http://localhost:8082`
- Docker-intern: `http://auth-manager:8082`

## Demo-Vereinfachungen

- HTTP statt HTTPS
- Token-Speicher nur im RAM des Auth Managers
- `authId` ist im Demo ein zufälliger opaque Identifier
- `/token`, `/refresh` und `/status` liegen im Demo auf demselben veröffentlichten Port wie `/login` und `/callback`
- kein vollständiges Logout-/Revocation-Konzept

Für Produktion sollte die Token-API des Auth Managers zusätzlich intern bzw. gerätegebunden abgesichert werden, z. B. über einen separaten nur intern erreichbaren Listener, mTLS oder eine vergleichbare Service-Authentisierung. Der Browser benötigt nur die Login- und Callback-Seite; Refresh Tokens dürfen den Auth Manager nicht verlassen.
