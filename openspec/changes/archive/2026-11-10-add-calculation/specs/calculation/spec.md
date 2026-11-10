# Spec Delta

## Purpose

Performs one exact decimal calculation per HTTP request on two operands, and reports every failure as RFC 9457
Problem Details, matching the Java service on the wire.

## ADDED Requirements

### Requirement: Calculation request
The service SHALL accept `POST /api/v1/calculations` with a JSON object holding `operation`, `a` and `b`, all three required. `a` and `b` SHALL be accepted as JSON numbers or numeric strings.

Evidence: Java `adapter/in/web/CalculationController.java:17,53-55`, `CalculationRequest.java:10-13`; test `CalculationControllerTest.rejectsAnInvalidRequestBeforeLookingAtTheOperation`.

#### Scenario: Number and string operands
- **WHEN** the body is `{"operation": "ADD", "a": 0.1, "b": "0.2"}`
- **THEN** the response is 200 with `result` `"0.3"`

#### Scenario: Missing field
- **WHEN** the body is `{"operation": "ADD", "a": 1}`
- **THEN** the response is 400 `invalid-request`

### Requirement: Lenient object fields
Unknown extra fields SHALL be ignored. For a duplicated key the last value SHALL win (source defect, ported faithfully).

Evidence: golden `body {"operation": "ADD", "a": 1, "b": 2, "extra": 1}`, `body {"operation": "ADD", "a": 1, "a": 9, "b": 2}`; MIGRATION-LOG Source defect 3.

#### Scenario: Duplicate key
- **WHEN** the body is `{"operation": "ADD", "a": 1, "a": 9, "b": 2}`
- **THEN** the response is 200 with `a` `"9"` and `result` `"11"`
### Requirement: Closed set of operations
The operations SHALL be exactly `ADD`, `SUBTRACT`, `MULTIPLY` and `DIVIDE`, in that order. A name SHALL match only when it equals an operation's name exactly, case included. Any other name SHALL be `unknown-operation`, whose detail lists the operations in order.

Evidence: Java `domain/Operation.java:8-46`, `UnknownOperationException.java:10`; tests `OperationTest.rejectsAnyOtherNameListingTheSupportedOnes`, `CalculationControllerTest.operationNamesAreCaseSensitive`.

#### Scenario: Wrong case
- **WHEN** `operation` is `"add"`
- **THEN** the response is 400 `unknown-operation` with detail `Unknown operation 'add'. Supported operations: ADD, SUBTRACT, MULTIPLY, DIVIDE.`
### Requirement: Non-string operation is coerced
A JSON number or boolean `operation` SHALL be read as its text (`5` as `"5"`, `1.5` as `"1.5"`, `true` as `"true"`) and therefore be `unknown-operation`. A `null` or object `operation` SHALL be `invalid-request`. This is a source defect, ported faithfully.

Evidence: golden `body {"operation": 5, "a": 1, "b": 2}`, `body {"operation": true, "a": 1, "b": 2}`; contract test `FrameworkDefaultsContractTest.a non-string operation is reported as unknown`; MIGRATION-LOG Source defect 2.

#### Scenario: Number operation
- **WHEN** `operation` is `5`
- **THEN** the response is 400 `unknown-operation` with detail starting `Unknown operation '5'.`

### Requirement: Exact arithmetic
`ADD`, `SUBTRACT` and `MULTIPLY` SHALL be exact. `DIVIDE` SHALL round to 34 significant digits, half-even (IEEE 754 decimal128).

Evidence: Java `domain/Operation.java:8-33`; tests `CalculationTest.performsPerBR3AndNormalisesPerBR4`, `OperationTest.dividesPerBR3`.

#### Scenario: Exact addition
- **WHEN** `ADD` is applied to `12345678901234567890.12345678901234567890` and `0.00000000000000000001`
- **THEN** `result` is `"12345678901234567890.12345678901234567891"`

#### Scenario: Rounded division
- **WHEN** `DIVIDE` is applied to `10000000000000000000000000000000015` and `10`
- **THEN** `result` is `"1000000000000000000000000000000002"`

### Requirement: Normalised result
The result SHALL have its trailing zeros stripped and SHALL be written in plain notation, never in exponent form.

Evidence: Java `domain/Calculation.java:21`, `adapter/in/web/CalculationResponse.java:13`; tests `CalculationTest.stripsTrailingZerosPerBR4`, `CalculationControllerTest.returnsTheExactResultNormalisedPerBR4`.

#### Scenario: Trailing zeros
- **WHEN** `ADD` is applied to `"1.50"` and `"1.50"`
- **THEN** `result` is `"3"`

#### Scenario: Exponent input
- **WHEN** `MULTIPLY` is applied to `1e3` and `"1"`
- **THEN** `result` is `"1000"`

### Requirement: Response echoes the request
A successful response SHALL be a JSON object with exactly `operation`, `a`, `b` and `result`, all strings. `a` and `b` SHALL be the operands as received, in plain notation with their scale kept.

Evidence: Java `adapter/in/web/CalculationResponse.java:5-13`; test `CalculationControllerTest.echoesOperandsAsReceivedInPlainNotation`.

#### Scenario: Echoed operands
- **WHEN** the body is `{"operation": "ADD", "a": 1e2, "b": "1.50"}`
- **THEN** the body is exactly `{"operation": "ADD", "a": "100", "b": "1.50", "result": "101.5"}`

### Requirement: Lenient operand strings
Operand strings SHALL be trimmed, and SHALL accept a leading `+`, an exponent, and a leading or trailing decimal point. `NaN`, hexadecimal, digit separators and non-ASCII digits SHALL be `invalid-request`.

Evidence: golden `ADD a=" +1 " b="1e1"`; contract test `FrameworkDefaultsContractTest.operand strings are trimmed and accept a plus sign and an exponent`; probed responses recorded as golden cases in this change (`"1."`, `"NaN"`, `"0x10"`, `"1_000"`).

#### Scenario: Trimmed and signed
- **WHEN** `a` is `" +1 "` and `b` is `"1e1"`
- **THEN** the response echoes `a` `"1"` and `b` `"10"` with `result` `"11"`

### Requirement: Operand limits
Each operand, as written, SHALL have at most 100 significant digits and a scale between -1000 and 1000. Otherwise the response SHALL be `operand-out-of-range` naming `a` or `b`, `a` first, without echoing the value.

Evidence: Java `domain/Calculation.java:8-9,24-30`, `OperandOutOfRangeException.java:3-9`; tests `CalculationTest.rejectsOperandsOnePastTheLimitsPerBR1`, `CalculationTest.rejectsTheMostNegativeScalePerBR1`.

#### Scenario: One past the scale limit
- **WHEN** `b` is `"1E+1001"`
- **THEN** the response is 400 `operand-out-of-range` with detail `Operand b must have at most 100 significant digits and a scale between -1000 and 1000.`

### Requirement: Huge exponents are cheap
An operand with a huge exponent SHALL be rejected before any arithmetic, so the response is immediate.

Evidence: test `CalculationControllerTest.rejectsHugeExponentsImmediatelyPerBR1`.

#### Scenario: Huge exponent
- **WHEN** both operands are `"1e999999999"` with `MULTIPLY`
- **THEN** the response is 400 `operand-out-of-range` within one second
### Requirement: Division by zero
Dividing by zero in any representation (`0`, `"0.00"`, `"-0"`, `"0E+3"`) SHALL be 422 `division-by-zero` with detail `Division by zero is undefined.`

Evidence: Java `domain/Operation.java:29-30`, `DivisionByZeroException.java:7`, `ProblemDetailsAdvice.java:63-65`; tests `OperationTest.rejectsDivisionByEveryZeroPerBR2`, `CalculationControllerTest.rejectsDivisionByZeroPerBR2`.

#### Scenario: Negative zero
- **WHEN** `DIVIDE` has `b` `"-0"`
- **THEN** the response is 422 `division-by-zero`

### Requirement: Request size limits
The body SHALL be at most 16,384 bytes, and each number or string value at most 1,000 characters, enforced while parsing. A breach SHALL be `invalid-request`.

Evidence: Java `src/main/resources/application.yaml:10-12`, `ProblemDetailsAdvice.java:33-35`; tests `CalculationControllerTest.rejectsAnOversizedBodyStatingTheLimits`, `acceptsABodyAtTheSizeLimit`, `rejectsAnOverlongValueWhileParsing`.

#### Scenario: One byte over
- **WHEN** a valid body is padded to 16,385 bytes
- **THEN** the response is 400 `invalid-request`

#### Scenario: Overlong value
- **WHEN** `a` is a 1,001-digit number
- **THEN** the response is 400 `invalid-request`

### Requirement: Body limit counts bytes
The body limit SHALL count UTF-8 bytes, although the invalid-request detail says characters. This is a source defect, ported faithfully.

Evidence: contract test `FrameworkDefaultsContractTest.the body limit counts bytes, not characters`; MIGRATION-LOG Source defect 1.

#### Scenario: Multi-byte characters
- **WHEN** a body of about 6,400 characters holds `€` signs totalling about 19,000 bytes
- **THEN** the response is 400 `invalid-request`
### Requirement: Strict JSON
The body SHALL be a single strict JSON object. Trailing tokens, a second document, comments, single quotes, `NaN`, leading zeros, a top-level `null`, and nesting deeper than 500 levels (the root counts) SHALL be `invalid-request`.

Evidence: probed responses recorded as golden cases in this change (`trailing garbage`, `two objects`, `comment`, `single quotes`, `NaN`, `leading zero`, `top-level null`, `nesting 499`, `nesting 500`).

#### Scenario: Trailing tokens
- **WHEN** the body is a valid request followed by ` xyz`
- **THEN** the response is 400 `invalid-request`

#### Scenario: Nesting limit
- **WHEN** an extra field nests 499 arrays
- **THEN** the response is 200
- **WHEN** an extra field nests 500 arrays
- **THEN** the response is 400 `invalid-request`

### Requirement: Error precedence
When a request has several problems, the service SHALL report the first of: `invalid-request`, `unknown-operation`, `operand-out-of-range`, `division-by-zero`.

Evidence: Java `application/PerformCalculation.java:10-11`, `domain/Calculation.java:11-22`; tests `CalculationControllerTest.rejectsAnInvalidRequestBeforeLookingAtTheOperation`, `reportsAnUnknownOperationBeforeOperandLimits`, `reportsOperandLimitsBeforeDivisionByZero`.

#### Scenario: Missing operand beats unknown operation
- **WHEN** the body is `{"operation": "NOPE", "a": 1}`
- **THEN** the response is 400 `invalid-request`

#### Scenario: Range beats division by zero
- **WHEN** `DIVIDE` has `a` `"1E+1001"` and `b` `0`
- **THEN** the response is 400 `operand-out-of-range`

### Requirement: Domain error format
Each domain error SHALL be `application/problem+json` with `type` `/problems/<slug>`, its fixed `title`, `status`, `detail`, and `instance` equal to the request path. The pairs are: invalid-request 400 "Invalid request"; unknown-operation 400 "Unknown operation"; operand-out-of-range 400 "Operand out of range"; division-by-zero 422 "Division by zero".

Evidence: Java `adapter/in/web/ProblemDetailsAdvice.java:33-35,58-70,82-91`; test `ApiDocumentationTest.documentedExampleIsTheRealResponse`.

#### Scenario: Invalid request body
- **WHEN** `a` is `"abc"`
- **THEN** the response is 400 with `type` `/problems/invalid-request`, `title` `Invalid request`, `instance` `/api/v1/calculations` and the fixed invalid-request detail

### Requirement: No internals in errors
No error detail SHALL contain a class or package name, a parser message, or the word `Exception`. An unexpected failure SHALL be logged and answered with 500, `type` `about:blank`, and detail `The calculation could not be completed.`

Evidence: Java `adapter/in/web/ProblemDetailsAdvice.java:73-79`; tests `CalculationControllerTest.reportsAnUnexpectedFailureWithoutInternals`, `CalculationControllerTest` (`assertProblem`).

#### Scenario: Unexpected failure
- **WHEN** handling fails with an unexpected exception
- **THEN** the response is 500 with detail `The calculation could not be completed.` and the exception is logged

### Requirement: Accepted request content types
A request body SHALL be accepted when its content type is a concrete type compatible with `application/json` or `application/*+json`, in any case, with any parameters, and with surrounding spaces. YAML and wildcard types SHALL NOT be accepted.

Evidence: golden `json with charset`, `vendor json`, and the content-type cases recorded in this change. No YAML: MIGRATION-LOG Accepted difference 1. Wildcards: Accepted difference 2 (Java answers 500, source defect 4).

#### Scenario: Vendor JSON
- **WHEN** the content type is `application/json-patch+json`
- **THEN** the request is processed

#### Scenario: YAML is not accepted
- **WHEN** the content type is `application/yaml`
- **THEN** the response is 415

#### Scenario: Wildcard is not accepted
- **WHEN** the content type is `*/*`
- **THEN** the response is 415 with detail `Content-Type '*/*;charset=UTF-8' is not supported.`

### Requirement: Unsupported request content type
Any other content type SHALL be 415 with an `Accept` header of `application/json, application/*+json` and detail `Content-Type '<type>' is not supported.` In that detail the type is lowercased, its parameters are joined with `;`, and `;charset=UTF-8` is added when no charset is given.

Evidence: golden `text/plain`, `no content type`, and the content-type cases recorded in this change; contract test `CalculationContractTest.rejects an unsupported media type`.

#### Scenario: Charset added
- **WHEN** the content type is `TEXT/PLAIN`
- **THEN** the detail is `Content-Type 'text/plain;charset=UTF-8' is not supported.`

#### Scenario: Missing and unparseable types
- **WHEN** there is no content type and the body is not empty
- **THEN** the detail is `Content-Type 'application/octet-stream' is not supported.`
- **WHEN** the content type is `garbage`, or names an unknown charset
- **THEN** the detail is `Could not parse Content-Type.`

### Requirement: Media type parameters
Media types in `Content-Type` and `Accept` SHALL be parsed as Spring does: parameter names lowercased, a parameter without a value ignored, `;` and `,` inside double quotes kept, `q` only from 0 to 1, and `charset` a known charset.

Evidence: golden `content type with quoted ';'`, `accept with quoted ','`, `accept 'application/json;foo'`, `accept 'APPLICATION/JSON;Charset=UTF-8'`, `accept 'application/json;charset=bogus'`, `content type 'application/json;charset=bogus'`.

#### Scenario: Quoted separator
- **WHEN** the content type is `multipart/form-data; boundary="a;b"`
- **THEN** the detail is `Content-Type 'multipart/form-data;boundary="a;b";charset=UTF-8' is not supported.`

#### Scenario: Parameter name case
- **WHEN** `Accept` is `APPLICATION/JSON;Charset=UTF-8`
- **THEN** the response content type is `application/json;charset=UTF-8`

### Requirement: Missing body
A request with no body and no content type SHALL be `invalid-request`. A request with no body and an unsupported content type SHALL still be 415.

Evidence: golden `no body, no content type`, `no body, text/plain`.

#### Scenario: Empty POST
- **WHEN** `POST /api/v1/calculations` is sent with no body and no content type
- **THEN** the response is 400 `invalid-request`

### Requirement: Declared charset
A request body with a declared charset that is not a Unicode encoding SHALL be decoded with that charset. Other bodies SHALL be read as bytes, so the size limit counts bytes.

Evidence: golden `latin-1 body`; contract test `FrameworkDefaultsContractTest.a declared Latin-1 body is decoded as Latin-1`.

#### Scenario: Latin-1
- **WHEN** the content type is `application/json;charset=ISO-8859-1` and `operation` is the Latin-1 byte for `é`
- **THEN** the response is 400 `unknown-operation` naming `é`
### Requirement: Response content type
`Accept` types SHALL be ordered by specificity, and the first compatible with `application/json` or `application/*+json` SHALL be echoed with its parameters. `*/*`, `application/*` or no `Accept` SHALL give `application/json`. A `q` of 0 SHALL NOT exclude a type. Errors SHALL ignore a parseable `Accept`.

Evidence: golden `accept problem+json, error` and the `Accept` cases recorded in this change.

#### Scenario: Vendor type echoed
- **WHEN** `Accept` is `application/vnd.a+json, application/json`
- **THEN** the response is 200 with content type `application/vnd.a+json`

#### Scenario: Specific before wildcard
- **WHEN** `Accept` is `application/*;q=1, application/vnd.c+json`
- **THEN** the response content type is `application/vnd.c+json`

#### Scenario: Errors ignore Accept
- **WHEN** `Accept` is `text/plain` and `operation` is unknown
- **THEN** the response is 400 `application/problem+json`

### Requirement: Not acceptable
When no `Accept` type is compatible, the response SHALL be a 406 problem with detail `Acceptable representations: [application/json, application/*+json].` and that list as its `Accept` header.

Evidence: golden `accept text/plain` and the `Accept` cases recorded in this change; contract test `CalculationContractTest.rejects an unacceptable response type`. No YAML in the list: MIGRATION-LOG Accepted difference 1.

#### Scenario: Nothing acceptable
- **WHEN** `Accept` is `text/plain`
- **THEN** the response is 406 problem with detail `Acceptable representations: [application/json, application/*+json].`

### Requirement: Unparseable Accept
When `Accept` cannot be parsed (for example `garbage`, or a `q` above 1), every response SHALL keep its status and headers but have no body and no content type: a success becomes 406, and an error keeps its own status.

Evidence: golden `accept 'garbage'`, `accept 'application/json, application/problem+json;q=2'`, and the `accept garbage, …` cases recorded in this change.

#### Scenario: Success
- **WHEN** `Accept` is `garbage` and the request is valid
- **THEN** the response is 406 with an empty body

#### Scenario: Error
- **WHEN** `Accept` is `garbage` and `operation` is unknown
- **THEN** the response is 400 with an empty body and no content type
### Requirement: Allowed methods
Only `POST` and `OPTIONS` SHALL be served on the endpoint. `OPTIONS` SHALL answer 200 with `Allow: POST,OPTIONS` and no body. Other methods SHALL be 405 with `Allow: POST` and detail `Method '<METHOD>' is not supported.`, with no body for `HEAD`.

Evidence: golden `GET calculations`, `HEAD calculations`, `PUT calculations`, `OPTIONS calculations`; test `CalculationControllerTest.rejectsAnUnsupportedMethod`. Exceptions: MIGRATION-LOG Accepted difference 4.

#### Scenario: Wrong method
- **WHEN** the endpoint is called with `PUT`
- **THEN** the response is 405 with `Allow: POST` and detail `Method 'PUT' is not supported.`

### Requirement: Trailing slash
The endpoint path with a trailing slash SHALL be a different, unknown path.

Evidence: golden `trailing slash`; contract test `FrameworkDefaultsContractTest.a trailing slash is a different path`.

#### Scenario: Trailing slash
- **WHEN** `POST /api/v1/calculations/` is called
- **THEN** the response is 404 with detail `No static resource api/v1/calculations.`
### Requirement: Unknown paths
A request to a path the service does not serve SHALL be a 404 problem with `type` `about:blank`, title `Not Found`, `instance` the request path, and detail `No static resource <path>.`, where the path is written without its leading or trailing slash.

Evidence: golden `GET /api/v1/nope`, `GET /`, `trailing slash`; test `CalculationControllerTest.rejectsAnUnknownPath`. The wording is copied by user decision (RB-30). Double slashes and path parameters: MIGRATION-LOG Accepted difference 3.

#### Scenario: Root path
- **WHEN** `GET /` is called
- **THEN** the detail is `No static resource .`
