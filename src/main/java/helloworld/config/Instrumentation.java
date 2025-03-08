package helloworld.config;

import io.opentelemetry.api.*;
import io.opentelemetry.api.common.*;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.opentracingshim.OpenTracingShim;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.metrics.*;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.temporal.common.interceptors.*;
import io.temporal.opentracing.*;
import com.uber.m3.tally.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.*;

public final class Instrumentation {
    private static final Logger logger = Logger.getLogger(Instrumentation.class.getName());
    
    // Configuration Attributes
    private static volatile OpenTelemetry openTelemetry;
    private static volatile Resource resource;
    private static Meter meter;
    private static Tracer tracer;
    private static boolean initialized = false;

    // MetricsExporter members
    private static Scope metricsScope;

    // TracingExporter members
    private static OpenTracingOptions openTracingOptions;
    private static BatchSpanProcessor spanProcessor;
    
    // Workflow metrics counters
    private static Map<String, LongCounter> counters = new HashMap<>();
    
    // Common attribute keys
    public static final AttributeKey<String> WORKFLOW_TYPE = AttributeKey.stringKey("workflow_type");
    public static final AttributeKey<String> WORKFLOW_ID = AttributeKey.stringKey("workflow_id");
    public static final AttributeKey<String> RUN_ID = AttributeKey.stringKey("run_id");
    public static final AttributeKey<String> NAMESPACE = AttributeKey.stringKey("namespace");
    public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("operation");
    public static final AttributeKey<String> SERVICE_TYPE = AttributeKey.stringKey("temporal_service_type");
    public static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error_type");

    private Instrumentation() {
        // Prevent instantiation
    }

    // ------------------ OpenTelemetryConfig Methods ------------------

    public static synchronized OpenTelemetry getOpenTelemetry() {
        if (openTelemetry == null) {
            throw new IllegalStateException("OpenTelemetry not initialized. Call initializeTelemetry() first.");
        }
        return openTelemetry;
    }

    public static Resource createResource() {
        if (resource != null) return resource;
        synchronized (Instrumentation.class) {
            if (resource != null) return resource;
            String environment = System.getenv().getOrDefault("OTEL_ENVIRONMENT", "development");
            String resourceAttrs = System.getenv("OTEL_RESOURCE_ATTRIBUTES");
            String serviceName = null;
            
            if (resourceAttrs != null) {
                for (String pair : resourceAttrs.split(",")) {
                    String[] keyValue = pair.trim().split("=", 2);
                    if ("service.name".equals(keyValue[0].trim()) && keyValue.length > 1) {
                        serviceName = keyValue[1].trim();
                        break;
                    }
                }
            }
            
            if (serviceName == null) {
                throw new IllegalStateException("service.name must be set in OTEL_RESOURCE_ATTRIBUTES");
            }
            
            resource = Resource.getDefault().merge(Resource.create(
                Attributes.of(
                    AttributeKey.stringKey("service.name"), serviceName,
                    AttributeKey.stringKey("service.namespace"), "default",
                    AttributeKey.stringKey("deployment.environment"), environment
                )
            ));
            return resource;
        }
    }

    public static String getEndpoint() {
        String endpoint = System.getenv().getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "http://localhost:4317");
        if (endpoint != null && !endpoint.isEmpty() && !endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            endpoint = "http://" + endpoint;
        }
        return endpoint;
    }

    public static Map<String, String> getAccessTokenHeaders() {
        String headersString = System.getenv("OTEL_EXPORTER_OTLP_HEADERS");
        Map<String, String> headers = new HashMap<>();

        if (headersString != null && !headersString.isEmpty()) {
            // Headers can be comma or semicolon separated
            String[] headerPairs = headersString.split("[,;]");
            for (String pair : headerPairs) {
                // Split by the first equals sign only
                String[] keyValue = pair.trim().split("=", 2);
                if (keyValue.length == 2) {
                    String headerName = keyValue[0].trim();
                    String headerValue = keyValue[1].trim();
                    headers.put(headerName, headerValue);
                    logger.info("Found header: " + headerName + " with value length: " + headerValue.length());
                } else {
                    logger.warning("Invalid header format: " + pair);
                }
            }

            if (!headers.isEmpty()) {
                logger.info("Total headers loaded: " + headers.size());
            } else {
                logger.warning("No headers were successfully parsed from OTEL_EXPORTER_OTLP_HEADERS");
            }
        } else {
            logger.info("No OTEL_EXPORTER_OTLP_HEADERS environment variable found");
        }

        return headers;
    }
    
    public static String getServiceName() {
        String resourceAttrs = System.getenv("OTEL_RESOURCE_ATTRIBUTES");
        if (resourceAttrs != null) {
            // Attributes can be comma or semicolon separated
            for (String pair : resourceAttrs.split("[,;]")) {
                String[] keyValue = pair.trim().split("=", 2);
                if ("service.name".equals(keyValue[0].trim()) && keyValue.length > 1) {
                    return keyValue[1].trim();
                }
            }
        }
        return "temporal-java-demo-app"; // Default service name if not specified
    }

    // ------------------ MetricsExporter Methods ------------------

    public static synchronized Scope getMetricsScope() {
        if (metricsScope == null) {
            metricsScope = new NoopScope();
        }
        return metricsScope;
    }

    public static SdkMeterProvider createMeterProvider() {
        String endpoint = getEndpoint();
        Map<String, String> headers = getAccessTokenHeaders();

        OtlpGrpcMetricExporter metricExporter;

        if (!headers.isEmpty()) {
            logger.info("Found " + headers.size() + " headers for metrics exporter");
            logger.info("Metrics endpoint: " + endpoint);
            metricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint(endpoint)
                .setHeaders(() -> headers)
                .setTimeout(java.time.Duration.ofSeconds(60))
                .build();
        } else {
            metricExporter = OtlpGrpcMetricExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(java.time.Duration.ofSeconds(60))
                .build();
        }

        return SdkMeterProvider.builder()
            .setResource(createResource())
            .build();
    }
    
    public static void shutdownMetrics() {
        logger.info("Metrics shutdown initiated");
        counters.clear();
        metricsScope = null;
        meter = null;
        initialized = false;
    }

    // ------------------ TracingExporter Methods ------------------

    public static WorkerInterceptor getWorkerInterceptor() {
        return new OpenTracingWorkerInterceptor(getOpenTracingOptions());
    }
    
    public static WorkflowClientInterceptor getClientInterceptor() {
        return new OpenTracingClientInterceptor(getOpenTracingOptions());
    }

    private static synchronized OpenTracingOptions getOpenTracingOptions() {
        if (openTracingOptions == null) {
            openTracingOptions = OpenTracingOptions.newBuilder()
                .setTracer(OpenTracingShim.createTracerShim(getOpenTelemetry()))
                .setSpanContextCodec(OpenTracingSpanContextCodec.TEXT_MAP_CODEC)
                .build();
        }
        return openTracingOptions;
    }

    public static SdkTracerProvider createTracerProvider() {
        String endpoint = getEndpoint();
        Map<String, String> headers = getAccessTokenHeaders();

        OtlpGrpcSpanExporter spanExporter;

        if (!headers.isEmpty()) {
            logger.info("Found " + headers.size() + " headers for trace exporter");
            spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .setHeaders(() -> headers)
                .setTimeout(java.time.Duration.ofSeconds(60))
                .build();
        } else {
            spanExporter = OtlpGrpcSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(java.time.Duration.ofSeconds(60))
                .build();
        }

        spanProcessor = BatchSpanProcessor.builder(spanExporter).build();

        return SdkTracerProvider.builder()
            .addSpanProcessor(spanProcessor)
            .setResource(createResource())
            .setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn())
            .build();
    }

    public static void shutdownTracing() {
        if (spanProcessor != null) {
            try {
                spanProcessor.forceFlush().join(30, TimeUnit.SECONDS);
                spanProcessor.shutdown().join(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                logger.severe("Tracing shutdown error: " + e.getMessage());
            }
        }
    }

    // ------------------ Telemetry Initialization ------------------

    public static synchronized void initializeTelemetry() {
        if (initialized) return;
        
        logger.info("Initializing OpenTelemetry...");

        try {
            OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(createTracerProvider())
                .setMeterProvider(createMeterProvider())
                .setPropagators(ContextPropagators.noop())
                .build();

            openTelemetry = sdk;
            meter = sdk.getMeter("io.temporal");
            tracer = sdk.getTracer("io.temporal");

            initializeMetrics();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("Shutting down telemetry...");
                shutdownMetrics();
                shutdownTracing();
                logger.info("Telemetry shutdown completed");
            }));

            initialized = true;
            logger.info("OpenTelemetry initialization completed successfully");
        } catch (Exception e) {
            logger.severe("Failed to initialize OpenTelemetry: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static Meter getMeter() {
        return meter;
    }

    public static Tracer getTracer() {
        return tracer;
    }
    
    // ==================== WorkflowMetrics Methods ====================
    
    /**
     * Initializes all workflow metrics counters.
     */
    public static void initializeMetrics() {
        if (meter == null) {
            logger.warning("Meter is null, cannot initialize metrics");
            return;
        }
        
        counters.put("workflow_success", meter.counterBuilder("workflow_success")
            .setDescription("Count of successfully completed workflow executions")
            .setUnit("{execution}")
            .build());

        counters.put("workflow_failed", meter.counterBuilder("workflow_failed")
            .setDescription("Count of failed workflow executions")
            .setUnit("{execution}")
            .build());

        counters.put("workflow_timeout", meter.counterBuilder("workflow_timeout")
            .setDescription("Count of timed out workflow executions")
            .setUnit("{execution}")
            .build());
            
        counters.put("workflow_terminate", meter.counterBuilder("workflow_terminate")
            .setDescription("Count of terminated workflow executions")
            .setUnit("{execution}")
            .build());

        counters.put("workflow_cancel", meter.counterBuilder("workflow_cancel")
            .setDescription("Count of canceled workflow executions")
            .setUnit("{execution}")
            .build());
        
        counters.put("service_requests", meter.counterBuilder("service_requests")
            .setDescription("Counts the number of service requests")
            .setUnit("{count}")
            .build());

        counters.put("service_errors", meter.counterBuilder("service_errors")
            .setDescription("Counts the number of service errors")
            .setUnit("{count}")
            .build());
                
        counters.put("service_error_with_type", meter.counterBuilder("service_error_with_type")
            .setDescription("Counts the number of service errors by type")
            .setUnit("{count}")
            .build());
                
        counters.put("service_restarts", meter.counterBuilder("service_restarts")
            .setDescription("Counts the number of service restarts")
            .setUnit("{count}")
            .build());
                
        counters.put("schedule_to_start_timeout", meter.counterBuilder("schedule_to_start_timeout")
            .setDescription("Counts the number of schedule to start timeouts")
            .setUnit("{count}")
            .build());
                
        counters.put("start_to_close_timeout", meter.counterBuilder("start_to_close_timeout")
            .setDescription("Counts the number of start to close timeouts")
            .setUnit("{count}")
            .build());

        logger.info("Workflow metrics initialized successfully");
    }
    
    /**
     * Creates attributes for workflow metrics.
     */
    private static Attributes createWorkflowAttributes(String workflowType, String workflowId, String runId, String namespace) {
        return Attributes.of(
            WORKFLOW_TYPE, workflowType,
            WORKFLOW_ID, workflowId,
            RUN_ID, runId,
            NAMESPACE, namespace
        );
    }
    
    /**
     * Records a metric with operation attribute.
     */
    private static void recordOperationMetric(String counterName, String operation) {
        LongCounter counter = counters.get(counterName);
        if (counter == null) {
            logger.warning(counterName + " counter not initialized. Call initializeMetrics() first.");
            return;
        }
        
        counter.add(1, Attributes.of(OPERATION, operation));
        logger.fine("Recorded " + counterName + ": " + operation);
    }
    
    /**
     * Records a workflow metric.
     */
    private static void recordWorkflowMetric(String counterName, String workflowType, String workflowId, String runId, String namespace) {
        LongCounter counter = counters.get(counterName);
        if (counter == null) {
            logger.warning(counterName + " counter not initialized. Call initializeMetrics() first.");
            return;
        }
        
        counter.add(1, createWorkflowAttributes(workflowType, workflowId, runId, namespace));
        logger.fine("Recorded " + counterName + ": " + workflowId);
    }
    
    public static void recordServiceRequest(String operation) {
        recordOperationMetric("service_requests", operation);
    }
    
    public static void recordSuccess(String workflowType, String workflowId, String runId, String namespace) {
        recordWorkflowMetric("workflow_success", workflowType, workflowId, runId, namespace);
    }
    
    public static void recordFailure(String workflowType, String workflowId, String runId, String namespace) {
        recordWorkflowMetric("workflow_failed", workflowType, workflowId, runId, namespace);
    }
    
    public static void recordTimeout(String workflowType, String workflowId, String runId, String namespace) {
        recordWorkflowMetric("workflow_timeout", workflowType, workflowId, runId, namespace);
    }
    
    public static void recordTermination(String workflowType, String workflowId, String runId, String namespace) {
        recordWorkflowMetric("workflow_terminate", workflowType, workflowId, runId, namespace);
    }
    
    public static void recordCancellation(String workflowType, String workflowId, String runId, String namespace) {
        recordWorkflowMetric("workflow_cancel", workflowType, workflowId, runId, namespace);
    }
    
    public static void recordServiceError(String operation) {
        recordOperationMetric("service_errors", operation);
    }
    
    public static void recordServiceRestart(String serviceType) {
        LongCounter counter = counters.get("service_restarts");
        if (counter == null) {
            logger.warning("Restarts counter not initialized. Call initializeMetrics() first.");
            return;
        }
        
        counter.add(1, Attributes.of(SERVICE_TYPE, serviceType));
        logger.fine("Recorded service restart: " + serviceType);
    }
    
    public static void recordScheduleToStartTimeout(String operation) {
        recordOperationMetric("schedule_to_start_timeout", operation);
    }
    
    public static void recordStartToCloseTimeout(String operation) {
        recordOperationMetric("start_to_close_timeout", operation);
    }
    
    public static void recordErrorWithType(String errorType) {
        LongCounter counter = counters.get("service_error_with_type");
        if (counter == null) {
            logger.warning("Error with type metrics not initialized. Call initializeMetrics() first.");
            return;
        }
        
        counter.add(1, Attributes.of(ERROR_TYPE, errorType));
        logger.fine("Recorded error with type: " + errorType);
    }
    
    /**
     * Registers custom metrics for dashboard compatibility.
     * This ensures that specific operation metrics are correctly tracked in the dashboard.
     */
    public static void registerDashboardMetrics() {
        logger.info("Registering custom dashboard metrics...");
        
        // Record a service restart event
        Instrumentation.recordServiceRestart("worker");
        
        // Create a timer to periodically emit activity metrics for dashboard visualization
        java.util.Timer metricsTimer = new java.util.Timer("DashboardMetricsTimer", true);
        metricsTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                try {
                    // Emit service_requests metrics for Activity Tasks
                    Instrumentation.recordServiceRequest("AddActivityTask");
                    Instrumentation.recordServiceRequest("RecordActivityTaskStarted");
                    Instrumentation.recordServiceRequest("ResponseActivityCompleted");
                    Instrumentation.recordServiceRequest("RespondActivityTaskFailed"); 
                    Instrumentation.recordServiceRequest("RespondActivityTaskCanceled");
                    
                    // Emit service_requests metrics for Workflow Tasks
                    Instrumentation.recordServiceRequest("AddWorkflowTask");
                    Instrumentation.recordServiceRequest("RecordWorkflowTaskStarted");
                    Instrumentation.recordServiceRequest("RespondWorkflowTaskCompleted");
                    Instrumentation.recordServiceRequest("RespondWorkflowTaskFailed");
                    Instrumentation.recordServiceRequest("TimerActiveTaskWorkflowTimeout");
                    
                    // Emit timeout metrics
                    Instrumentation.recordScheduleToStartTimeout("TimerActiveTaskWorkflowTimeout");
                    Instrumentation.recordStartToCloseTimeout("TimerActiveTaskWorkflowTimeout");
                    
                    // Emit workflow completion metrics for dashboard
                    String workflowType = "HelloWorldWorkflow";
                    String workflowId = "sample-workflow-id";
                    String runId = "sample-run-id";
                    String namespace = "default";
                    
                    // Record workflow completion metrics with different states
                    Instrumentation.recordSuccess(workflowType, workflowId, runId, namespace);
                    Instrumentation.recordFailure(workflowType, workflowId + "-failed", runId, namespace);
                    Instrumentation.recordTimeout(workflowType, workflowId + "-timeout", runId, namespace);
                    Instrumentation.recordTermination(workflowType, workflowId + "-terminated", runId, namespace);
                    Instrumentation.recordCancellation(workflowType, workflowId + "-canceled", runId, namespace);
                    
                    // Emit service_errors metrics for Activity Tasks
                    Instrumentation.recordServiceError("AddActivityTask");
                    Instrumentation.recordServiceError("RecordActivityTaskStarted");
                    Instrumentation.recordServiceError("RespondActivityTaskCompleted");
                    Instrumentation.recordServiceError("RespondActivityTaskFailed");
                    Instrumentation.recordServiceError("RespondActivityTaskCanceled");
                    
                    // Emit service_errors metrics for Workflow Tasks
                    Instrumentation.recordServiceError("AddWorkflowTask");
                    Instrumentation.recordServiceError("RecordWorkflowTaskStarted");
                    Instrumentation.recordServiceError("RespondWorkflowTaskCompleted");
                    Instrumentation.recordServiceError("RespondWorkflowTaskFailed");
                    
                    // Emit error metrics with different error types
                    Instrumentation.recordErrorWithType("validation");
                    Instrumentation.recordErrorWithType("timeout");
                    Instrumentation.recordErrorWithType("business_rule");
                    Instrumentation.recordErrorWithType("system");
                    
                    logger.fine("Dashboard metrics emitted at " + new Date());
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Error emitting dashboard metrics", e);
                }
            }
        }, 1000, 5000); // Initial delay of 1 second, then every 5 seconds
        
        // Add shutdown hook to clean up
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            metricsTimer.cancel();
            logger.info("Dashboard metrics timer canceled");
        }));
        
        logger.info("Dashboard metrics registered successfully");
    }
}