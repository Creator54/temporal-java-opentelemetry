package helloworld.config;

import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.SimpleSslContextBuilder;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Date;

public class TemporalConfig {
    private static final Logger logger = Logger.getLogger(TemporalConfig.class.getName());
    
    // Default configuration values
    private static final class Defaults {
        static final String TARGET = "localhost:7233";
        static final String NAMESPACE = "default";
        static final String TASK_QUEUE = "hello-world-task-queue";
    }

    // Environment variable names
    private static final class EnvVars {
        static final String HOST_URL = "TEMPORAL_HOST_URL";
        static final String NAMESPACE = "TEMPORAL_NAMESPACE";
        static final String TASK_QUEUE = "TEMPORAL_TASK_QUEUE";
        static final String TLS_CERT = "TEMPORAL_TLS_CERT";
        static final String TLS_KEY = "TEMPORAL_TLS_KEY";
    }

    private static WorkflowServiceStubs service;
    private static WorkflowClient client;

    public static WorkflowClient getWorkflowClient() {
        if (client == null) {
            initializeDefault();
        }
        return client;
    }

    public static WorkflowClient getWorkflowClient(WorkflowServiceStubsOptions stubOptions) {
        return getWorkflowClient(stubOptions, null);
    }

    public static WorkflowClient getWorkflowClient(
            WorkflowServiceStubsOptions stubOptions,
            WorkflowClientOptions clientOptions) {
        initializeWithOptions(stubOptions, clientOptions);
        return client;
    }

    public static WorkflowServiceStubs getService() {
        if (service == null) {
            initializeDefault();
        }
        return service;
    }

    private static synchronized void initializeDefault() {
        if (service != null && client != null) {
            return;
        }
        try {
            service = initializeWorkflowServiceStubs(WorkflowServiceStubsOptions.newBuilder());
            client = initializeWorkflowClient(null);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Temporal configuration", e);
        }
    }

    private static synchronized void initializeWithOptions(
            WorkflowServiceStubsOptions stubOptions,
            WorkflowClientOptions clientOptions) {
        try {
            WorkflowServiceStubsOptions.Builder builder = WorkflowServiceStubsOptions.newBuilder()
                .setMetricsScope(stubOptions.getMetricsScope())
                .setEnableKeepAlive(stubOptions.getEnableKeepAlive())
                .setKeepAliveTime(stubOptions.getKeepAliveTime())
                .setKeepAliveTimeout(stubOptions.getKeepAliveTimeout())
                .setKeepAlivePermitWithoutStream(stubOptions.getKeepAlivePermitWithoutStream());

            configureEndpoint(builder);
            
            service = WorkflowServiceStubs.newServiceStubs(builder.build());
            client = initializeWorkflowClient(clientOptions);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initialize Temporal configuration", e);
        }
    }

    private static WorkflowServiceStubs initializeWorkflowServiceStubs(WorkflowServiceStubsOptions.Builder options) {
        configureEndpoint(options);
        return WorkflowServiceStubs.newServiceStubs(options.build());
    }

    private static void configureEndpoint(WorkflowServiceStubsOptions.Builder options) {
        String targetEndpoint = System.getenv(EnvVars.HOST_URL);
        String certPath = System.getenv(EnvVars.TLS_CERT);
        String keyPath = System.getenv(EnvVars.TLS_KEY);

        if (targetEndpoint != null && certPath != null && keyPath != null) {
            // Cloud configuration
            logger.info("Configuring for Temporal Cloud at: " + targetEndpoint);
            options.setTarget(targetEndpoint);
            configureTls(options);
        } else if (targetEndpoint != null) {
            // Cloud URL provided but missing certificates
            logger.warning("Temporal Cloud URL provided but missing TLS certificates. Please set " + 
                         EnvVars.TLS_CERT + " and " + EnvVars.TLS_KEY);
            throw new RuntimeException("Missing TLS certificates for Temporal Cloud connection");
        } else {
            // Local configuration
            logger.info("Using local Temporal server at: " + Defaults.TARGET);
            options.setTarget(Defaults.TARGET);
        }
    }

    private static void configureTls(WorkflowServiceStubsOptions.Builder options) {
        String certPath = System.getenv(EnvVars.TLS_CERT);
        String keyPath = System.getenv(EnvVars.TLS_KEY);

        if (certPath != null && keyPath != null) {
            try {
                // Use FileInputStream to read the certificate and key files
                try (InputStream clientCertInputStream = new FileInputStream(certPath);
                     InputStream clientKeyInputStream = new FileInputStream(keyPath)) {
                    
                    // Use SimpleSslContextBuilder.forPKCS8() as recommended for Temporal Cloud
                    SslContext sslContext = SimpleSslContextBuilder
                        .forPKCS8(clientCertInputStream, clientKeyInputStream)
                        .build();
                    
                    options.setSslContext(sslContext);
                    logger.info("TLS configuration successful");
                }
            } catch (IOException e) {
                logger.log(Level.SEVERE, "Failed to read TLS certificate or key files", e);
                throw new RuntimeException("Failed to read TLS certificate or key files", e);
            }
        }
    }

    private static WorkflowClient initializeWorkflowClient(WorkflowClientOptions options) {
        String namespace = getEnvOrDefault(EnvVars.NAMESPACE, Defaults.NAMESPACE);
        WorkflowClientOptions.Builder builder = WorkflowClientOptions.newBuilder()
            .setNamespace(namespace);

        if (options != null) {
            if (options.getInterceptors() != null) {
                builder.setInterceptors(options.getInterceptors());
            }
            if (options.getIdentity() != null) {
                builder.setIdentity(options.getIdentity());
            }
            if (options.getDataConverter() != null) {
                builder.setDataConverter(options.getDataConverter());
            }
        }

        logger.info("Initializing Temporal client with namespace: " + namespace);
        return WorkflowClient.newInstance(service, builder.build());
    }

    private static String getEnvOrDefault(String envVar, String defaultValue) {
        String value = System.getenv(envVar);
        return value != null ? value : defaultValue;
    }

    public static String getTaskQueue() {
        return getEnvOrDefault(EnvVars.TASK_QUEUE, Defaults.TASK_QUEUE);
    }

    public static String getNamespace() {
        return getEnvOrDefault(EnvVars.NAMESPACE, Defaults.NAMESPACE);
    }

    private TemporalConfig() {
        // Prevent instantiation - use static methods
    }
}