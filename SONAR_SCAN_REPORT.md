# SonarQube / SonarScanner Scan Report (aosp_platform_packages_services_Car-344497)

## Executive status
- SonarScanner **configuration file exists**: `sonar-project.properties`
- Java runtime **is available**: OpenJDK 17
- SonarScanner CLI **was not preinstalled**, but was downloaded and verified to run.
- A **full SonarCloud scan was attempted** and **failed immediately due to authentication**:
  - Error: `HTTP 403` while querying `https://api.sonarcloud.io/analysis/jres?...`
  - Root cause: `SONAR_TOKEN` is a placeholder and not a valid token.

Because the scan failed before analysis began, there are **no findings to categorize** (severity/CWE/module). This document lists what is needed to run a complete scan and then retrieve/categorize findings.

---

## Evidence collected (verified in this repo)

### 1) Repo-level Sonar configuration
File: `sonar-project.properties`
```properties
sonar.projectKey=akshatkavia-ai_aosp_platform_packages_services_Car-344497
sonar.organization=akshatkavia-ai

# Scan only smaller stable folder first
sonar.sources=service/src/com/android/car

sonar.sourceEncoding=UTF-8

# Prevent Java bytecode failure
sonar.java.binaries=.

# Reduce scanner complexity
sonar.c.file.suffixes=-
sonar.cpp.file.suffixes=-

# Exclude generated/problematic files
sonar.exclusions=**/out/**,**/.repo/**,**/generated/**,**/*.aidl
```

Notes:
- Current configuration is set to analyze **only**: `service/src/com/android/car`
- `sonar.java.binaries=.` is used as a workaround to avoid bytecode resolution failures; this may reduce rule effectiveness for some Java rules.

### 2) Environment variables present
File: `.env`
```env
PORT=3000
SONAR_HOST_URL=https://sonarcloud.io/organizations/akshatkavia-ai/projects
SONAR_TOKEN=SONAR_TOKEN
SONAR_PROJECT_KEY=f82b959092a288543fa2a3d595df17f3921ec7c2
SONAR_ORGANIZATION=akshatkavia-ai
SONAR_BRANCH=kavia-aosp/pie-release-4763
```

Issues:
- `SONAR_TOKEN=SONAR_TOKEN` is clearly a placeholder (not a real token).
- `SONAR_HOST_URL` is set to an **organization/projects UI URL**, not the SonarCloud API base. For SonarScanner it should be:
  - `https://sonarcloud.io` (or your self-hosted `https://<sonarqube-host>`)

### 3) Scanner availability & Java
- `sonar-scanner` was **not** found in PATH initially (`command not found`)
- Java is available:
  - `openjdk version "17.0.18" ...`
- A local SonarScanner CLI was downloaded and verified:
  - SonarScanner CLI `7.1.0.4889`
  - Runs successfully with `-v`

---

## Scan attempt outcome (what failed)
When attempting to run the scanner against SonarCloud, the scan failed before performing analysis:

Key error excerpt:
```
ERROR Failed to query JRE metadata: GET https://api.sonarcloud.io/analysis/jres?os=linux&arch=x86_64 failed with HTTP 403.
Please check the property sonar.token or the environment variable SONAR_TOKEN.
```

Interpretation:
- SonarCloud rejected the request due to **invalid/missing token**.
- No `.scannerwork` directory and no local report artifacts were generated because it failed at initialization.

---

## What is needed to proceed (minimal requirements)

### A) Valid SonarCloud / SonarQube authentication
Provide **one** of the following (preferred order):
1. **SonarCloud token** with permission to analyze the project:
   - Create at: SonarCloud → User → Security → Generate Token
   - Must have rights for:
     - `akshatkavia-ai_aosp_platform_packages_services_Car-344497` (project)
     - and the org `akshatkavia-ai` as applicable
2. For self-hosted SonarQube: a token for that server with “Execute Analysis” permission.

Then set:
- `SONAR_TOKEN=<real_token_value>` (CI secret)
- `SONAR_HOST_URL=https://sonarcloud.io` (or your SonarQube server base URL)

### B) Correct project identifiers
The repo’s `sonar-project.properties` already contains:
- `sonar.projectKey=akshatkavia-ai_aosp_platform_packages_services_Car-344497`
- `sonar.organization=akshatkavia-ai`

Confirm that:
- The project key exists in SonarCloud (or is allowed to be created by CI).
- The token has access to that org/project.

### C) Optional but recommended: source scope and Java bytecode
Currently:
- Only `service/src/com/android/car` is scanned (not the whole repository).
- `sonar.java.binaries=.` is a workaround.

To perform a truly “complete” scan of the whole repository, you likely need:
- Expand `sonar.sources` to include additional modules:
  - `car-lib/src`, `car-support-lib/src`, `obd2-lib/src`, `tools/**`, `tests/**`, etc.
- Provide compiled class files (`sonar.java.binaries=<path(s) to compiled output>`) by running the appropriate AOSP/Gradle build steps prior to scanning.
  - Without bytecode, some Java rules may be skipped or less precise.

---

## How to run the scan once credentials are provided

### SonarCloud (example)
From repo root:
```bash
export SONAR_HOST_URL="https://sonarcloud.io"
export SONAR_TOKEN="***"   # secret

sonar-scanner \
  -Dsonar.host.url="$SONAR_HOST_URL" \
  -Dsonar.token="$SONAR_TOKEN"
```

Notes:
- Prefer `sonar.token` over deprecated `sonar.login`.
- Keep `sonar-project.properties` as the authoritative baseline, and override only when needed.

---

## Producing the requested categorized findings (severity / CWE / module)
Once a scan succeeds, the findings can be collected and categorized in one of these ways:

1) **Via SonarCloud Web API** (best for automation):
- Query issues by severity and component/module:
  - `/api/issues/search?componentKeys=<projectKey>&severities=BLOCKER,CRITICAL,MAJOR,MINOR,INFO&ps=500&p=1...`
- Map rules to CWE when available:
  - `/api/rules/show?key=<ruleKey>` often includes CWE references in rule metadata (language/rule dependent).

2) **Export from UI**:
- Use SonarCloud UI Issues page filters, export, then group offline.

This repo currently does **not** include any script/tooling to do this export and grouping automatically; it would need to be added after scan access is available.

---

## Instructions for future agent
1. Obtain and set a real `SONAR_TOKEN` (secret) with access to the SonarCloud org/project.
2. Set `SONAR_HOST_URL=https://sonarcloud.io` (not the org/projects UI URL).
3. Re-run `sonar-scanner` from repo root.
4. After a successful analysis, use SonarCloud Web API (`/api/issues/search` + `/api/rules/show`) to export issues and generate the requested report grouped by:
   - Severity (BLOCKER/CRITICAL/MAJOR/MINOR/INFO)
   - CWE (from rule metadata where present)
   - Impacted module (derive from file path prefix: `service/`, `car-lib/`, `car-support-lib/`, `tests/`, `tools/`, etc.)
