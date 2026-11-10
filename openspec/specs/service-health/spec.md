# service-health Specification

## Purpose
Lets load balancers and orchestrators check that the service is up, through the same actuator endpoints and
wire format as the Java service, and exposes no other operational endpoint.

## Requirements

### Requirement: Health status
`GET /actuator/health` SHALL answer 200 with `{"groups": ["liveness", "readiness"], "status": "UP"}` and no `components` or `details`, with or without a trailing slash, and ignoring any query string.

Evidence: Java `build.gradle.kts:25` (actuator defaults, no configuration); test `ServiceHealthSmokeTest.startedInstanceReportsUpWithoutDetails`; golden `GET /actuator/health`, `GET /actuator/health/`, and the query case recorded in this change.

#### Scenario: Up
- **WHEN** `GET /actuator/health` is called
- **THEN** the response is 200 with `status` `UP`, `groups` `["liveness", "readiness"]`, and no `components` or `details`

### Requirement: Health groups
`GET /actuator/health/liveness` and `/actuator/health/readiness` SHALL answer 200 with exactly `{"status": "UP"}`, with or without a trailing slash. Group names SHALL be case-sensitive.

Evidence: golden `GET /actuator/health/liveness`, `GET /actuator/health/readiness`, and the group cases recorded in this change; contract test `HealthContractTest.each health group reports UP`.

#### Scenario: Liveness
- **WHEN** `GET /actuator/health/liveness/` is called
- **THEN** the response is 200 with body `{"status": "UP"}`

### Requirement: Unknown health path
Any other path under `/actuator/health/` (an unknown group, a different case, a nested path) SHALL answer 404 with no body and no content type. This is the only 404 that is not Problem Details.

Evidence: golden `GET /actuator/health/nope` and the group cases recorded in this change; contract test `HealthContractTest.an unknown health group is 404 with an empty body`.

#### Scenario: Unknown group
- **WHEN** `GET /actuator/health/LIVENESS` is called
- **THEN** the response is 404 with an empty body

### Requirement: Actuator index
`GET /actuator` SHALL answer 200 with `_links` holding exactly `self`, `health` and `health-path`, pointing at `/actuator`, `/actuator/health` and `/actuator/health/{*path}`. Only `health-path` is templated. `/actuator/` SHALL be the generic 404 problem.

Evidence: test `ServiceHealthSmokeTest.actuatorIndexLinksOnlyToHealth`; golden `GET /actuator`, `GET /actuator/`.

#### Scenario: Links
- **WHEN** `GET /actuator` is called
- **THEN** `_links` holds exactly `self`, `health` and `health-path`, and only `health-path` has `templated` true

### Requirement: Index links follow the Host header
Each index href SHALL start with `http://` and the `Host` header's name and port, the port read as a number (no digits read as 0) and dropped when it is 80. `X-Forwarded-*` headers SHALL be ignored.

Evidence: golden `actuator index Host header`, `actuator index Host 'example.org:80'`, `actuator index Host 'example.org:'`, `actuator index Host '[::1]:9090'`, `actuator index X-Forwarded headers`.

#### Scenario: Custom port
- **WHEN** `GET /actuator` is called with `Host: example.org:9999`
- **THEN** `_links.self.href` is `http://example.org:9999/actuator`

#### Scenario: Default port dropped
- **WHEN** `GET /actuator` is called with `Host: example.org:80`
- **THEN** `_links.self.href` is `http://example.org/actuator`

### Requirement: Nothing else is exposed
Every other actuator endpoint (`/actuator/env`, `beans`, `info`, `metrics` and the rest) SHALL be the generic 404 problem.

Evidence: test `ServiceHealthSmokeTest.otherOperationalEndpointsAreNotServed`; golden `GET /actuator/env`, `GET /actuator/beans`, `GET /actuator/info`, `GET /actuator/metrics`.

#### Scenario: Environment hidden
- **WHEN** `GET /actuator/env` is called
- **THEN** the response is 404 `application/problem+json` with detail `No static resource actuator/env.`

### Requirement: Actuator media types
Actuator responses SHALL offer `application/vnd.spring-boot.actuator.v3+json` (the default), `application/vnd.spring-boot.actuator.v2+json` and `application/json`, chosen from `Accept` as for calculations (quality, then specificity, then order) and echoed with their parameters.

Evidence: golden `GET /actuator/health` and the `Accept` cases recorded in this change; contract test `HealthContractTest.health uses the actuator media type`.

#### Scenario: Plain JSON
- **WHEN** `Accept` is `application/json`
- **THEN** the content type is `application/json`

#### Scenario: Quality decides
- **WHEN** `Accept` is `application/json;q=0.5, application/vnd.spring-boot.actuator.v2+json`
- **THEN** the content type is `application/vnd.spring-boot.actuator.v2+json`

### Requirement: Actuator not acceptable
When no offered type is acceptable, an actuator path SHALL answer a 406 problem listing the three types, with that list as its `Accept` header. An unparseable `Accept` SHALL give 406 with that `Accept` header and no body. Negotiation SHALL happen before the unknown-path 404.

Evidence: golden `actuator accept 'text/plain'`, `actuator accept 'application/vnd.foo+json'`, `actuator accept 'garbage'`, `actuator unknown group accept garbage`, `actuator index accept text/plain`.

#### Scenario: Text only
- **WHEN** `Accept` is `text/plain` on `/actuator/health`
- **THEN** the response is 406 with detail `Acceptable representations: [application/vnd.spring-boot.actuator.v3+json, application/vnd.spring-boot.actuator.v2+json, application/json].`

#### Scenario: Unknown group with nothing acceptable
- **WHEN** `Accept` is `garbage` on `/actuator/health/nope`
- **THEN** the response is 406 with no body

### Requirement: Actuator methods
Actuator paths SHALL serve `GET` and `HEAD` (headers only). `OPTIONS` SHALL answer 200 with `Allow: GET,HEAD,OPTIONS` and no body. Other methods SHALL be 405 with `Allow: GET` and detail `Method '<METHOD>' is not supported.`

Evidence: golden `HEAD /actuator/health`, `POST /actuator/health`, `PUT /actuator`, `OPTIONS /actuator/health/liveness`, `POST /actuator/health/nope`. TRACE: MIGRATION-LOG Accepted difference 4.

#### Scenario: POST
- **WHEN** `POST /actuator/health` is called
- **THEN** the response is 405 with `Allow: GET`

#### Scenario: HEAD
- **WHEN** `HEAD /actuator/health` is called
- **THEN** the response is 200 with content type `application/vnd.spring-boot.actuator.v3+json` and no body
