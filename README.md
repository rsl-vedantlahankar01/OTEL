# User CRUD OpenTelemetry Assignment

## Build and run

Use JDK 17 or newer and Maven. From the project directory:

```sh
mvn clean package
mvn spring-boot:run
```

The API listens on `http://localhost:8080`. It uses in-memory H2 with three seed users. Restarting resets the data.

| Method | Endpoint | Result |
|---|---|---|
| POST | `/users` | 201 on creation, 409 for a duplicate email |
| GET | `/users/{id}` | 200 when found, 404 when absent |
| DELETE | `/users/{id}` | 204 when deleted, 404 when absent |

## Run with the OpenTelemetry Java Agent

Download [OpenTelemetry Java Agent 2.22.0](https://repo.maven.apache.org/maven2/io/opentelemetry/javaagent/opentelemetry-javaagent/2.22.0/opentelemetry-javaagent-2.22.0.jar) outside the project and set its absolute path below. Stop the plain application before starting it again with the agent.

Use the instructor's OTLP receiver endpoint if available. For local verification, start the [Grafana OTEL LGTM sandbox](https://github.com/grafana/docker-otel-lgtm) with Docker:

```sh
docker run -d --name crud-otel-observability \
  -p 127.0.0.1:3000:3000 -p 127.0.0.1:4318:4318 \
  grafana/otel-lgtm:0.41.1
```

```sh
export OTEL_JAVA_AGENT=/absolute/path/to/opentelemetry-javaagent.jar
export OTEL_SERVICE_NAME=crud-otel-assignment
export OTEL_EXPORTER_OTLP_ENDPOINT=http://127.0.0.1:4318
export OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf
export OTEL_TRACES_EXPORTER=otlp OTEL_METRICS_EXPORTER=otlp OTEL_LOGS_EXPORTER=otlp
export OTEL_TRACES_SAMPLER=parentbased_always_on OTEL_METRIC_EXPORT_INTERVAL=5000

export OTEL_INSTRUMENTATION_COMMON_DEFAULT_ENABLED=false
export OTEL_INSTRUMENTATION_TOMCAT_ENABLED=true OTEL_INSTRUMENTATION_SERVLET_ENABLED=true
export OTEL_INSTRUMENTATION_SPRING_WEBMVC_ENABLED=true
export OTEL_INSTRUMENTATION_OPENTELEMETRY_API_ENABLED=true
export OTEL_INSTRUMENTATION_LOGBACK_APPENDER_ENABLED=true

mvn spring-boot:run "-Dspring-boot.run.jvmArguments=-javaagent:${OTEL_JAVA_AGENT}"
```

If your receiver requires authentication, set `OTEL_EXPORTER_OTLP_HEADERS` through your environment using the receiver's configuration. Keep credentials out of this repository.

## Generate traffic

Run these commands on a fresh start. The new user is normally ID 4 because the app seeds IDs 1 to 3. Use the ID returned by the POST if it differs.

```sh
curl -i -X POST http://localhost:8080/users \
  -H 'Content-Type: application/json' \
  -d '{"name":"Assignment Test","email":"otel-test@example.invalid"}'
curl -i http://localhost:8080/users/4
curl -i http://localhost:8080/users/999
curl -i -X DELETE http://localhost:8080/users/4
curl -i -X DELETE http://localhost:8080/users/999
```

## What is instrumented

The Java Agent owns the SDK/exporters and creates the automatic HTTP server span. `GET /users/{id}` uses that server span. Create and delete each start one manual service span in the active HTTP context. The manually created span records the exception type and error status when the service throws. The exception message and stack trace can contain an email or other input, so the telemetry records the type alone. A successful create attaches the ID after the database assigns it.

The service records one increment in `users.operation.count` and one millisecond duration in the `users.operation.duration` histogram per completed service call. Both use the `operation` and `status` labels. Requests rejected during input validation never reach the service. The application logs start, success and failure, with user IDs once available. The agent's Logback integration exports these logs with trace and span context.

## Where to view telemetry

Open local Grafana at <http://localhost:3000> or use the dashboard URL for your own backend. Select a recent time range after sending requests. Open the [User CRUD Operations dashboard](http://localhost:3000/d/crud-otel-assignment/user-crud-operations) in the configured local sandbox for count and mean duration by operation and outcome.

- **Request traces (Tempo).** Run the TraceQL query `{ resource.service.name = "crud-otel-assignment" && name = "delete-user" }`. In the trace waterfall, expand `DELETE /users/{id}` to see the child span. For a missing user, inspect the child span's error status and `exception.type` event.
- **Numerical measurements (Prometheus).** In Explore, find `users_operation_count_total` and the `users_operation_duration_milliseconds` histogram series. Group by `operation` and `status`. The histogram's `_count` is the number of observations and its `_sum` is the total elapsed time in milliseconds.
- **Application logs (Loki).** In Explore, use `{service_name="crud-otel-assignment"} |= "User operation"`. Filter for the trace ID shown in Tempo to find the exact operation logs.
