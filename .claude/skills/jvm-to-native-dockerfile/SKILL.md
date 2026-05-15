---
name: jvm-to-native-dockerfile
description: Convert a JVM-based Quarkus Dockerfile to a native Mandrel build. Use when converting JVM Dockerfiles to native, creating native Quarkus builds, switching from JVM to GraalVM/Mandrel, or generating native-image Dockerfiles.
allowed-tools: Bash, Read, Write, Edit, WebFetch
---

Convert a JVM-based Quarkus Dockerfile to a native Mandrel build for the notifications-backend project.

This skill transforms Dockerfiles that use `ubi9/openjdk-21` JVM images into native-image builds using Mandrel (Red Hat's GraalVM distribution), producing smaller containers with faster startup.

## Reference Template

The canonical native Dockerfile template is `docker/Dockerfile.notifications-connector-drawer.jvm` in the notifications-backend repository. All generated Dockerfiles should follow this structure.

## When Invoked

### Step 1: Collect User Input

Ask the user for the following information interactively. Present defaults where available.

**1a. Source Dockerfile**

List all available JVM Dockerfiles that have NOT yet been converted to native:

```bash
# List .jvm files in the docker/ directory of the project
ls docker/Dockerfile.*.jvm
```

Show the list and ask which one to convert. If the Dockerfile already contains `Mandrel` or `quarkus.package.type=native`, warn the user it appears to already be a native build and confirm they want to proceed.

**1b. Mandrel Version**

Ask for the Mandrel version. Default: `23.1.11.0-Final`

Examples of valid versions: `23.1.10.0-Final`, `23.1.11.0-Final`, `24.0.1.0-Final`

**1c. Java Version**

Ask for the Java version. Default: `21`. Valid values: `17` or `21`.

**1d. Mandrel SHA256 Checksum**

**IMPORTANT:** The SHA256 checksum must be asked as a **follow-up question** after the initial `AskUserQuestion` call, because `AskUserQuestion` only supports predefined options (dropdown/multiple choice), not free-text input for a 64-character hex string.

After collecting 1a-1c and 1e via `AskUserQuestion`, ask the user directly (via regular text message) for the SHA256 checksum. This is **required** for secure, production-ready Dockerfiles.

Guide the user to find it:
- Visit: `https://github.com/graalvm/mandrel/releases/tag/mandrel-{VERSION}`
- Look for the SHA256 checksum file: `mandrel-java{JAVA_VERSION}-linux-amd64-{VERSION}.tar.gz.sha256`
- Click it to view the checksum for the tarball `mandrel-java{JAVA_VERSION}-linux-amd64-{VERSION}.tar.gz`
- Copy the 64-character hexadecimal hash (the checksum is FOR the .tar.gz file, not the .sha256 file itself)
- Example for 23.1.11.0-Final Java 21: `11c27dd5b16b5154336418d63c0bc90781ccf1c7e0ad454126ada2325bd320ca`

**Validation:** Must be exactly 64 hexadecimal characters (SHA256 hash format).

**Fallback if user provides wrong SHA256:** If the Docker build fails with SHA256 mismatch, automatically fetch the correct checksum:
```bash
curl -sL "https://github.com/graalvm/mandrel/releases/download/mandrel-{VERSION}/mandrel-java{JAVA_VERSION}-linux-amd64-{VERSION}.tar.gz.sha256"
```
Extract the hash (first 64 characters), update the Dockerfile, and inform the user of the correction.

**1e. Output Strategy**

Ask the user how to write the output:
- **Overwrite**: Replace the existing `.jvm` file in-place (the file name keeps `.jvm` extension but content becomes native)
- **New file**: Create a new file with `.native` extension (e.g., `Dockerfile.notifications-mcp.native`)

Default: Overwrite (to match the pattern established by the drawer connector).

### Step 2: Fetch Mandrel Prerequisites

Attempt to fetch the prerequisites from the Mandrel release page:

```bash
# Try to fetch the release page for the specified version
curl -sL "https://github.com/graalvm/mandrel/releases/tag/mandrel-{VERSION}"
```

Or use WebFetch:
- URL: `https://github.com/graalvm/mandrel/releases/tag/mandrel-{VERSION}`
- Prompt: "Extract the Prerequisites section for Red Hat/UBI9 systems. List ONLY the Red Hat package names (ending in -devel, -static, or base packages like gcc). Do NOT include Ubuntu (packages ending in -dev) or Arch Linux alternatives. Return only the package names as a list: glibc-devel, zlib-devel, gcc, freetype-devel, libstdc++-static."

Parse the response to extract the list of required Red Hat/UBI9 system packages.

**Fallback**: If the fetch fails (network error, 404, timeout), use this known-good default package list:
```
glibc-devel
zlib-devel
gcc
freetype-devel
libstdc++-static
```

Inform the user which package list is being used (fetched or fallback).

### Step 3: Parse the Source Dockerfile

Read the source JVM Dockerfile and extract:

**3a. Module name**: Extract from the `-pl :notifications-{module}` flag in the `mvnw` command.
- Example: `-pl :notifications-mcp` -> module name is `notifications-mcp`

**3b. Module directory**: Derive from the `quarkus-app` COPY paths.
- Pattern: `/home/jboss/{directory}/target/quarkus-app/`
- Example: `/home/jboss/mcp/target/quarkus-app/` -> directory is `mcp`
- The directory is typically the module name with the `notifications-` prefix stripped
- For connector modules: `notifications-connector-email` -> directory `connector-email`
- **CRITICAL PATH VALIDATION:** Ensure you extract the container path (`/home/jboss/{directory}/target`), NOT a localhost-specific path. The find command will run inside Docker, not on your local filesystem.

**3c. Maven build flags**: Extract all `-D` flags and other options from the `mvnw` command line.
- Common flags: `-Dmaven.test.skip`, `-Dcheckstyle.skip`, `-DskipTests`
- Preserve ALL existing flags from the source Dockerfile

**3d. Custom runtime steps**: Check for any non-standard steps in the runtime stage:
- CA certificate installation (e.g., `mtls-ca-validators.crt` and `update-ca-trust`)
- Additional package installations
- Extra COPY directives
- Environment variable overrides

These MUST be preserved in the native Dockerfile, placed in the runtime stage after the base image upgrade and before the license copy.

### Step 4: Generate the Native Dockerfile

Before generating, get the current date for the comment:
```bash
date +"%B %Y"
```

Generate the Dockerfile using this exact template structure. Replace all placeholders with values from previous steps (including the SHA256 from Step 1d).

```dockerfile
####
# This Dockerfile is used in order to build a container that runs the Quarkus application in native mode with Mandrel
###

# Build the project with Mandrel (UBI9-based)
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest AS build
USER root

# Install required dependencies for native-image compilation
# Note: curl-minimal is already installed in ubi9-minimal

# Set Mandrel version and architecture
# Using latest Mandrel release for Java {JAVA_VERSION} (as of {CURRENT_DATE})
ENV MANDREL_VERSION={MANDREL_VERSION}
ENV JAVA_VERSION={JAVA_VERSION}
ENV ARCH=linux-amd64

# Install Mandrel {MANDREL_VERSION} requested dependencies (list available at https://github.com/graalvm/mandrel/releases/tag/mandrel-{MANDREL_VERSION})
RUN microdnf install -y \
  {PACKAGE_1} \
  {PACKAGE_2} \
  ... \
  && microdnf clean all

# Install additional dependencies to unzip Mandrel artefact
RUN microdnf install -y \
  tar \
  gzip \
  findutils \
  && microdnf clean all

# Download and install Mandrel
ARG MANDREL_SHA256={MANDREL_SHA256}
RUN curl -L -o /tmp/mandrel.tar.gz \
  https://github.com/graalvm/mandrel/releases/download/mandrel-${MANDREL_VERSION}/mandrel-java${JAVA_VERSION}-${ARCH}-${MANDREL_VERSION}.tar.gz \
  && echo "${MANDREL_SHA256} /tmp/mandrel.tar.gz" | sha256sum -c - \
  && tar -xzf /tmp/mandrel.tar.gz -C /usr/local \
  && rm /tmp/mandrel.tar.gz

# Set environment variables
ENV JAVA_HOME=/usr/local/mandrel-java${JAVA_VERSION}-${MANDREL_VERSION}
ENV GRAALVM_HOME=${JAVA_HOME}
ENV PATH=${JAVA_HOME}/bin:${PATH}

COPY . /home/jboss
WORKDIR /home/jboss
# '-Dquarkus.native.container-build=false' option disables containerized native image building, because the build is already running inside the Mandrel builder container
RUN ./mvnw -s .mvn/settings.xml clean package \
    -Dquarkus.package.type=native \
    -Dquarkus.native.container-build=false \
    -Dquarkus.kafka.snappy.enabled=true \
    {PRESERVED_MAVEN_FLAGS} \
    -pl :{MODULE_NAME} -am --no-transfer-progress && \
    find /home/jboss/{MODULE_DIR}/target -name "*-runner" -exec mv {} /home/jboss/application \;

# Build the container with minimal runtime (UBI9-based)
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

WORKDIR /
# Update the base image packages
USER root
# See https://www.mankier.com/8/microdnf
RUN microdnf upgrade --refresh --nodocs --setopt=install_weak_deps=0 -y
RUN microdnf clean all

{CUSTOM_RUNTIME_STEPS}

# Create licenses directory and copy license file
RUN mkdir -p /licenses
COPY --from=build --chown=185:0 /home/jboss/LICENSE.txt /licenses/LICENSE

# Copy the native executable (moved to a fixed location during build)
COPY --from=build --chown=185:0 /home/jboss/application /application

EXPOSE 8080
USER 185

ENTRYPOINT ["/application"]
```

**Placeholder substitutions:**
- `{CURRENT_DATE}`: Output from `date +"%B %Y"` (e.g., "May 2026")
- `{MANDREL_SHA256}`: SHA256 checksum from Step 1d (64 hex characters)

**Key transformations from JVM to native:**

| JVM Dockerfile | Native Dockerfile |
|---|---|
| `ubi9/openjdk-21:latest` build image | `ubi9/ubi-minimal:latest` + Mandrel installation |
| `ubi9/openjdk-21-runtime:latest` runtime image | `ubi9/ubi-minimal:latest` (no JVM needed) |
| `mvnw clean package` | `mvnw clean package -Dquarkus.package.type=native -Dquarkus.native.container-build=false -Dquarkus.kafka.snappy.enabled=true` |
| 4 COPY layers for quarkus-app (lib, jar, app, quarkus) | Single COPY of the native `application` binary |
| `ENTRYPOINT ["sh", "-c", "java $JAVA_OPTIONS -jar ..."]` | `ENTRYPOINT ["/application"]` |
| `ENV JAVA_OPTIONS=...` | Removed (no JVM) |
| `ENV LANG=...` | Removed (not needed for native) |
| `USER jboss` between upgrade and COPY | Removed (not needed) |

**Maven flags handling:**
- ALWAYS add these three native-specific flags (they are NOT in the JVM Dockerfiles):
  - `-Dquarkus.package.type=native`
  - `-Dquarkus.native.container-build=false`
  - `-Dquarkus.kafka.snappy.enabled=true`
- PRESERVE these flags from the source if present:
  - `-Dmaven.test.skip`
  - `-Dcheckstyle.skip`
  - `-DskipTests`
  - Any other `-D` flags
- Format: Put each flag on its own line with `\` continuation, indented with 4 spaces

**Custom runtime steps (`{CUSTOM_RUNTIME_STEPS}`):**
- If the source Dockerfile has CA certificate steps (like `connector-email` and `recipients-resolver`), include them after `microdnf clean all` and before the license copy:
  ```dockerfile
  # Add RedHat CAs on OS truststore (check https://certs.corp.redhat.com/ for more details)
  COPY --from=build /home/jboss/{path}/mtls-ca-validators.crt /etc/pki/ca-trust/source/anchors/mtls-ca-validators.crt
  RUN update-ca-trust
  ```
- If no custom steps exist, leave that section empty (no blank placeholder lines).

### Step 5: Write the Output

Write the generated Dockerfile to the chosen location:

- **Overwrite mode**: Write directly to the source file path (e.g., `docker/Dockerfile.notifications-mcp.jvm`)
- **New file mode**: Write to a new path with `.native` extension (e.g., `docker/Dockerfile.notifications-mcp.native`)

After writing, show the user the full generated Dockerfile content and confirm success.

### Step 6: Offer to Test the Build

After writing the Dockerfile, use `AskUserQuestion` to ask what testing the user wants:

```
Question: "Would you like to test the native Docker image?"
Options:
1. "Build and run" - Build the image and test-run the container
2. "Build only" - Build the image but don't run it
3. "Skip" - Skip all testing
```

Based on the user's choice:

**6a. If "Build and run" or "Build only":**

Run the Docker build:
```bash
# Run from the repository root
docker build -f {OUTPUT_DOCKERFILE_PATH} -t test-native-{MODULE_NAME} .
```

- This build will take a long time (10-30+ minutes for native compilation). Warn the user.
- Run the build in the background using `run_in_background: true`
- **Build Progress Monitoring**: After starting the build, inform the user they can monitor progress by reading the output file periodically:
  ```bash
  tail -50 /tmp/claude-*/tasks/{task_id}.output
  ```
  The build progresses through these stages:
  1. Downloading Mandrel (1-2 minutes)
  2. Maven dependency resolution (2-5 minutes)
  3. Native image analysis (5-10 minutes) - looks for `[2/8] Performing analysis...`
  4. Method compilation (10-20 minutes) - looks for `[6/8] Compiling methods...`
  5. Image creation (1-2 minutes) - looks for `[8/8] Creating image...`
  
- When the build completes, you'll be notified automatically
- If the build succeeds, report success and image size using:
  ```bash
  docker images test-native-{MODULE_NAME} --format "{{.Repository}}:{{.Tag}} - Size: {{.Size}}"
  ```
- If the build fails, capture and display the full error output. Common failures:
  - **Missing Mandrel version**: 404 when downloading -> suggest checking available versions at https://github.com/graalvm/mandrel/releases
  - **SHA256 mismatch**: Wrong checksum -> automatically fetch the correct SHA256 from the release page, update the Dockerfile, and restart the build
  - **Out of memory during native-image**: Suggest adding `-Dquarkus.native.native-image-xmx=8g` to the mvnw command
  - **Missing native dependencies**: Check the error for missing `.so` files and suggest adding the corresponding `-devel` package
  - **Reflection/serialization errors**: Suggest checking Quarkus native compatibility guides

**6b. If "Build and run" and build succeeded:**

Test-run the container:
```bash
docker run --rm -p 8080:8080 --name test-native-{MODULE_NAME} test-native-{MODULE_NAME} &
```

- Run in background using `run_in_background: true`
- Wait a few seconds (5s) then check logs:
  ```bash
  docker logs test-native-{MODULE_NAME} 2>&1 | grep -E "started in|Listening on"
  ```
- Check container status:
  ```bash
  docker ps --filter "name=test-native-{MODULE_NAME}" --format "{{.Names}}: {{.Status}}"
  ```
- Report startup time if visible in logs (native images typically start in 1-2s)
- Expected warnings: Database connection errors are normal without external services
- Stop the container after verification:
  ```bash
  docker stop test-native-{MODULE_NAME}
  ```

**6c. If "Skip":**

Skip to Step 7 (Summary). Do not build or run anything.

### Step 7: Summary

Present a summary to the user:
- Source Dockerfile path
- Output Dockerfile path
- Mandrel version used
- Java version used
- SHA256 checksum used
- Prerequisites source (fetched or fallback)
- Module name and directory
- Preserved custom steps (if any)
- Test results (if tests were run)

### Step 8: Reflection

**IMPORTANT**: Only execute this step AFTER all build and testing is complete (Step 6 finished) and summary is presented (Step 7 completed). Do not ask before the build completes.

Use `AskUserQuestion` to ask if the user wants a self-evaluation:

```
Question: "Would you like me to reflect on how the skill execution went?"
Options:
1. "Yes, reflect" - Perform detailed self-evaluation of the skill execution
2. "No, skip" - End the skill (do not reflect)
```

**If user selects "Yes, reflect"**, perform a self-evaluation:

**Evaluate your execution across these dimensions:**

1. **Completeness**: Did you successfully execute all required steps?
   - Input collection: Were all parameters gathered (including SHA256)?
   - Prerequisites fetch: Did the fetch work or did you fall back?
   - Parsing: Were module name, directory, and flags correctly extracted?
   - Generation: Did the Dockerfile follow the template structure?
   - SHA256 validation: Was the checksum format validated correctly?
   - Testing: If build/run tests were requested, did they complete?

2. **Correctness**: Did the outputs match expectations?
   - Does the generated Dockerfile structurally match the finalized example?
   - Were all custom runtime steps preserved correctly?
   - Were Maven flags properly combined (native-specific + preserved)?
   - Did the find command use the correct module directory path?
   - Is the current date correctly inserted?
   - Did the Docker build succeed? If not, what failed?
   - Did the container run successfully? If not, what failed?

3. **Issues Encountered**: What problems occurred during execution?
   - Missing or ambiguous data in source Dockerfile
   - Prerequisites fetch failures
   - Unexpected Dockerfile structure
   - SHA256 validation or format issues
   - Build failures (Mandrel download, SHA256 mismatch, compilation, OOM, missing dependencies)
   - Runtime failures (container startup, application errors)
   - Error handling needed but not documented

4. **Skill Clarity**: Were the skill instructions clear and sufficient?
   - Ambiguous steps that required interpretation
   - Missing guidance for edge cases
   - Steps that could be more specific or detailed
   - Assumptions made due to unclear instructions

5. **Improvement Opportunities**: What would make this skill better?
   - Additional error handling needed
   - New module patterns to document
   - Template adjustments required
   - Automation opportunities (e.g., auto-detect custom steps)
   - Features from Extensibility Notes that would have helped
   - Build/runtime issues that should be documented

**Format your reflection:**
- Brief assessment (2-3 sentences) of overall execution quality
- Specific observations about what went well
- Specific issues or gaps encountered (especially build/runtime failures)
- Concrete suggestions for skill improvement (if any)

This reflection helps continuously improve the skill based on real execution patterns, including failures.

### Step 9: Implement Skill Improvements

**Only execute this step if Step 8 (Reflection) was performed.**

After completing the reflection, ask the user which improvements they want to implement:

Use `AskUserQuestion` with options based on your reflection findings. Present up to 4 concrete improvement suggestions as options. Each option should:
- Clearly state what will be added/changed
- Explain why it's beneficial
- Be actionable (can be implemented immediately)

**Example question structure:**
```
Question: "Based on the reflection, which skill improvements would you like to implement?"
Header: "Improvements"
MultiSelect: true (allow multiple selections)
Options:
1. "Add [specific error] to troubleshooting" - "Documents [error type] encountered in this run"
2. "Add path validation warning" - "Prevents container path vs localhost path mistakes"
3. "Improve [step X] guidance" - "Makes [specific instruction] clearer"
4. "None" - "Skip all improvements"
```

**If user selects improvements:**

For each selected improvement:
1. Make the specific changes to the skill file
2. Show what was added/modified (quote the new/updated text)
3. Explain where it was added (which section/step)

**After all improvements are applied:**

Present a summary:
- List of improvements implemented
- Which sections of the skill were updated
- Confirmation that the skill file was saved

**If user selects "None":**

Skip to the end. Do not modify the skill file.

## Finalized Example

The following is a complete, production-ready example of a converted Dockerfile (notifications-mcp module with SHA256 verification):

```dockerfile
####
# This Dockerfile is used in order to build a container that runs the Quarkus application in native mode with Mandrel
###

# Build the project with Mandrel (UBI9-based)
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest AS build
USER root

# Install required dependencies for native-image compilation
# Note: curl-minimal is already installed in ubi9-minimal

# Set Mandrel version and architecture
# Using latest Mandrel release for Java 21 (as of May 2026)
ENV MANDREL_VERSION=23.1.11.0-Final
ENV JAVA_VERSION=21
ENV ARCH=linux-amd64

# Install Mandrel 23.1.11.0-Final requested dependencies (list available at https://github.com/graalvm/mandrel/releases/tag/mandrel-23.1.11.0-Final)
RUN microdnf install -y \
  glibc-devel \
  zlib-devel \
  gcc \
  freetype-devel \
  libstdc++-static \
  && microdnf clean all

# Install additional dependencies to unzip Mandrel artefact
RUN microdnf install -y \
  tar \
  gzip \
  findutils \
  && microdnf clean all

# Download and install Mandrel
ARG MANDREL_SHA256=11c27dd5b16b5154336418d63c0bc90781ccf1c7e0ad454126ada2325bd320ca
RUN curl -L -o /tmp/mandrel.tar.gz \
  https://github.com/graalvm/mandrel/releases/download/mandrel-${MANDREL_VERSION}/mandrel-java${JAVA_VERSION}-${ARCH}-${MANDREL_VERSION}.tar.gz \
  && echo "${MANDREL_SHA256} /tmp/mandrel.tar.gz" | sha256sum -c - \
  && tar -xzf /tmp/mandrel.tar.gz -C /usr/local \
  && rm /tmp/mandrel.tar.gz

# Set environment variables
ENV JAVA_HOME=/usr/local/mandrel-java${JAVA_VERSION}-${MANDREL_VERSION}
ENV GRAALVM_HOME=${JAVA_HOME}
ENV PATH=${JAVA_HOME}/bin:${PATH}

COPY . /home/jboss
WORKDIR /home/jboss
# '-Dquarkus.native.container-build=false' option disables containerized native image building, because the build is already running inside the Mandrel builder container
RUN ./mvnw -s .mvn/settings.xml clean package \
    -Dquarkus.package.type=native \
    -Dquarkus.native.container-build=false \
    -Dquarkus.kafka.snappy.enabled=true \
    -Dmaven.test.skip \
    -Dcheckstyle.skip \
    -pl :notifications-mcp -am --no-transfer-progress && \
    find /home/jboss/mcp/target -name "*-runner" -exec mv {} /home/jboss/application \;

# Build the container with minimal runtime (UBI9-based)
FROM registry.access.redhat.com/ubi9/ubi-minimal:latest

WORKDIR /
# Update the base image packages
USER root
# See https://www.mankier.com/8/microdnf
RUN microdnf upgrade --refresh --nodocs --setopt=install_weak_deps=0 -y
RUN microdnf clean all

# Create licenses directory and copy license file
RUN mkdir -p /licenses
COPY --from=build --chown=185:0 /home/jboss/LICENSE.txt /licenses/LICENSE

# Copy the native executable (moved to a fixed location during build)
COPY --from=build --chown=185:0 /home/jboss/application /application

EXPOSE 8080
USER 185

ENTRYPOINT ["/application"]
```

Use this example to validate the structure and content of generated Dockerfiles.

## Error Handling

- **Source file not found**: List available `.jvm` files and ask user to pick again
- **Source file already native**: Warn and ask for confirmation before proceeding
- **Invalid Mandrel version format**: Must match pattern `XX.X.XX.X-Final` (e.g., `23.1.11.0-Final`). Suggest checking https://github.com/graalvm/mandrel/releases
- **Invalid SHA256 format**: Must be exactly 64 hexadecimal characters. Ask user to verify the checksum from the Mandrel release page
- **Prerequisites fetch failure**: Use fallback package list and inform user
- **Module name not found in Dockerfile**: If `-pl :` pattern not found, ask user to specify the module name manually
- **Module directory not found**: If quarkus-app COPY path not found, derive directory from module name by stripping `notifications-` prefix. If that directory does not exist in the repo, ask user to specify manually
- **Docker not available**: If `docker` command not found, skip test steps and inform user
- **Write permission denied**: Report error and suggest checking file permissions

## Native-Image Compilation Troubleshooting

Common native-image build failures and their solutions:

### SSL/OAuth/SecureRandom Initialization Errors

**Symptom:**
```
Error: Detected an instance of Random/SeedGenerator in the image heap.
Instances created during image generation have cached seed values and don't behave as expected.
```

**Cause:** SSL, OAuth, or crypto components are being initialized at build time instead of runtime. Native images require these to be initialized when the application starts, not when it's compiled.

**Solution:** Add runtime initialization flag to the Maven command in the Dockerfile:
```dockerfile
RUN ./mvnw -s .mvn/settings.xml clean package \
    -Dquarkus.package.type=native \
    -Dquarkus.native.container-build=false \
    -Dquarkus.kafka.snappy.enabled=true \
    -Dquarkus.native.additional-build-args=--initialize-at-run-time=<className> \
    ...
```

**Common classes that need runtime initialization:**
- `com.nimbusds.oauth2.sdk.http.HTTPRequest` (OAuth/OIDC services - **notifications-backend**)
- `sun.security.ssl.SSLContextImpl` (SSL/TLS connections)
- `java.security.SecureRandom` (Cryptographic operations)

**Example for notifications-backend:**
```
-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.nimbusds.oauth2.sdk.http.HTTPRequest
```

Multiple classes can be specified comma-separated:
```
-Dquarkus.native.additional-build-args=--initialize-at-run-time=com.nimbusds.oauth2.sdk.http.HTTPRequest,sun.security.ssl.SSLContextImpl
```

### Out of Memory During Native-Image Compilation

**Symptom:**
```
Error: Image build ran out of memory
```

**Solution:** Increase native-image memory limit:
```
-Dquarkus.native.native-image-xmx=8g
```

### Missing Native Dependencies

**Symptom:**
```
Error: cannot find -lstdc++
Error: ld: library not found
```

**Solution:** Add missing `-devel` or `-static` package to the prerequisites installation in the Dockerfile.

### Path Validation

**CRITICAL:** The `find` command in the Dockerfile MUST use container paths (`/home/jboss/{MODULE_DIR}/target`), NOT localhost-specific paths (e.g., `/home/username/...`). The Dockerfile executes inside Docker, not on your local filesystem. Always verify the path matches the container structure.

## Known Module Mappings

For reference, these are the known module-to-directory mappings in the notifications-backend project:

| Maven Module | Directory |
|---|---|
| notifications-aggregator | aggregator |
| notifications-backend | backend |
| notifications-connector-drawer | connector-drawer |
| notifications-connector-email | connector-email |
| notifications-connector-google-chat | connector-google-chat |
| notifications-connector-microsoft-teams | connector-microsoft-teams |
| notifications-connector-pagerduty | connector-pagerduty |
| notifications-connector-servicenow | connector-servicenow |
| notifications-connector-slack | connector-slack |
| notifications-connector-splunk | connector-splunk |
| notifications-connector-webhook | connector-webhook |
| notifications-engine | engine |
| notifications-mcp | mcp |
| notifications-recipients-resolver | recipients-resolver |

The pattern is: strip the `notifications-` prefix to get the directory name.

## Modules with Custom Runtime Steps

These modules have additional steps that MUST be preserved in the native Dockerfile:

- **notifications-connector-email**: CA certificate installation (`recipients-resolver/src/main/resources/mtls-ca-validators.crt`)
- **notifications-recipients-resolver**: CA certificate installation (`recipients-resolver/src/main/resources/mtls-ca-validators.crt`)

## Extensibility Notes

<!-- Future enhancements to consider:
- Auto-fetch SHA256: Automatically fetch the .sha256 file from GitHub releases URL:
  https://github.com/graalvm/mandrel/releases/download/mandrel-{VERSION}/mandrel-java{JAVA}-{ARCH}-{VERSION}.tar.gz.sha256
  This would eliminate the need for manual input while keeping security.
- Architecture detection: Support linux-aarch64 in addition to linux-amd64.
  Could detect via `uname -m` and map x86_64 -> linux-amd64, aarch64 -> linux-aarch64.
  Would require fetching arch-specific SHA256 checksums.
- Custom build flags: Allow user to specify additional Maven flags interactively
  (e.g., -Dquarkus.native.native-image-xmx=8g for memory-constrained builds).
- Batch conversion: Convert all remaining JVM Dockerfiles in one run,
  iterating through the list and applying the same Mandrel version/Java version to each.
  Would need to handle per-module custom steps correctly and prompt for SHA256 once.
- Build-time resource limits: Add --memory and --cpus flags to docker build
  for environments with limited resources.
- Multi-stage caching: Add a Maven dependency download stage before the native build
  to improve layer caching on repeated builds.
-->
