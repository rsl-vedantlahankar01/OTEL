package com.example.observability.service;

import com.example.observability.dto.UserRequest;
import com.example.observability.dto.UserResponse;
import com.example.observability.entity.User;
import com.example.observability.exception.DuplicateEmailException;
import com.example.observability.exception.UserNotFoundException;
import com.example.observability.repository.UserRepository;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

@Service
public class UserService {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserService.class);
    private static final String INSTRUMENTATION_SCOPE = "com.example.observability.users";

    private final UserRepository userRepository;
    private final Tracer tracer;
    private final LongCounter operationCount;
    private final DoubleHistogram operationDuration;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
        var openTelemetry = GlobalOpenTelemetry.get();
        tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        Meter meter = openTelemetry.getMeter(INSTRUMENTATION_SCOPE);
        operationCount = meter.counterBuilder("users.operation.count")
                .setDescription("Completed user service operations")
                .setUnit("{operation}")
                .build();
        operationDuration = meter.histogramBuilder("users.operation.duration")
                .setDescription("Elapsed time for user service operations")
                .setUnit("ms")
                .build();
    }

    @Transactional
    public UserResponse createUser(UserRequest request) {
        return observe("create", "create-user", null, () -> {
            if (userRepository.existsByEmail(request.getEmail())) {
                throw new DuplicateEmailException("Email already exists: " + request.getEmail());
            }

            User user = new User();
            user.setName(request.getName());
            user.setEmail(request.getEmail());

            User saved = userRepository.save(user);
            return toResponse(saved);
        });
    }

    @Transactional(readOnly = true)
    public UserResponse getUser(Long id) {
        return observe("get", null, id, () -> {
            User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
            return toResponse(user);
        });
    }

    @Transactional
    public void deleteUser(Long id) {
        observe("delete", "delete-user", id, () -> {
            User user = userRepository.findById(id).orElseThrow(() -> new UserNotFoundException("User not found with id: " + id));
            userRepository.delete(user);
            return null;
        });
    }

    private UserResponse observe(String operation, String spanName, Long userId, Supplier<UserResponse> action) {
        boolean manualSpan = spanName != null;
        Span span = manualSpan ? tracer.spanBuilder(spanName).startSpan() : Span.current();
        if (manualSpan) {
            span.setAttribute("operation.name", spanName);
            if (userId != null) {
                span.setAttribute("user.id", userId.longValue());
            }
        }

        long start = System.nanoTime();
        try (Scope ignored = manualSpan ? span.makeCurrent() : Scope.noop()) {
            String status = "error";
            try {
                logOperation(operation, "started", userId, null);
                UserResponse result = action.get();
                Long completedId = userId == null ? result.getId() : userId;
                if (manualSpan && userId == null) {
                    span.setAttribute("user.id", completedId.longValue());
                }
                status = "success";
                logOperation(operation, status, completedId, null);
                return result;
            } catch (RuntimeException failure) {
                if (manualSpan) {
                    span.setStatus(StatusCode.ERROR);
                    // Record the exception type to keep request data out of telemetry.
                    span.addEvent("exception", Attributes.of(
                            AttributeKey.stringKey("exception.type"),
                            failure.getClass().getName()));
                }
                logOperation(operation, status, userId, failure.getClass().getSimpleName());
                throw failure;
            } finally {
                Attributes attributes = Attributes.builder().put("operation", operation).put("status", status).build();
                operationCount.add(1, attributes);
                operationDuration.record((System.nanoTime() - start) / 1_000_000.0, attributes);
            }
        } finally {
            if (manualSpan) {
                span.end();
            }
        }
    }

    private void logOperation(String operation, String status, Long userId, String errorType) {
        SpanContext context = Span.current().getSpanContext();
        String message = "User operation operation=" + operation + " status=" + status;
        if (userId != null) {
            message += " user.id=" + userId;
        }
        if (context.isValid()) {
            message += " trace_id=" + context.getTraceId() + " span_id=" + context.getSpanId();
        }
        if (errorType == null) {
            LOGGER.info(message);
        } else {
            LOGGER.warn("{} error.type={}", message, errorType);
        }
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getName(), user.getEmail());
    }
}
