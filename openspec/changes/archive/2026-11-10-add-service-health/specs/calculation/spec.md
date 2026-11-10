# Spec Delta

## MODIFIED Requirements

### Requirement: Response content type
`Accept` types SHALL be ordered by quality, then by specificity, then as written, and the first compatible with `application/json` or `application/*+json` SHALL be echoed with its parameters. `*/*`, `application/*` or no `Accept` SHALL give `application/json`. A `q` of 0 SHALL NOT exclude a type. Errors SHALL ignore a parseable `Accept`.

Evidence: golden `accept problem+json, error` and the `Accept` cases recorded in item 3; quality order: golden `accept '*/*;q=0.9, application/vnd.a+json;q=0.1'` and `actuator accept 'application/json;q=0.5, application/vnd.spring-boot.actuator.v2+json'` (item 4).

#### Scenario: Vendor type echoed
- **WHEN** `Accept` is `application/vnd.a+json, application/json`
- **THEN** the response is 200 with content type `application/vnd.a+json`

#### Scenario: Specific before wildcard
- **WHEN** `Accept` is `application/*;q=1, application/vnd.c+json`
- **THEN** the response content type is `application/vnd.c+json`

#### Scenario: Quality before order
- **WHEN** `Accept` is `application/json;q=0.5, application/vnd.a+json`
- **THEN** the response content type is `application/vnd.a+json`

#### Scenario: Quality before specificity
- **WHEN** `Accept` is `*/*;q=0.9, application/vnd.a+json;q=0.1`
- **THEN** the response content type is `application/json`

#### Scenario: Errors ignore Accept
- **WHEN** `Accept` is `text/plain` and `operation` is unknown
- **THEN** the response is 400 `application/problem+json`
