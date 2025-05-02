package helloworld.workers;

import helloworld.config.TemporalConfig;
import helloworld.config.Instrumentation;
import helloworld.workflows.impl.HelloWorldWorkflowImpl;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.worker.WorkerOptions;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class HelloWorldWorker implements AutoCloseable {
    private final WorkerFactory factory;
    private final Worker worker;
    private final CountDownLatch shutdownLatch;
    private volatile boolean isShuttingDown = false;

    public HelloWorldWorker() {
        // Use the centralized telemetry initialization and configuration
        this.factory = WorkerFactory.newInstance(
            TemporalConfig.getWorkflowClient(), 
            Instrumentation.getWorkerFactoryOptions()
        );
        
        // Configure worker with task slots
        WorkerOptions workerOptions = WorkerOptions.newBuilder()
            .setMaxConcurrentWorkflowTaskExecutionSize(Instrumentation.MAX_WORKFLOW_TASK_SLOTS)
            .setMaxConcurrentActivityExecutionSize(Instrumentation.MAX_ACTIVITY_TASK_SLOTS)
            .build();

        this.worker = factory.newWorker(TemporalConfig.getTaskQueue(), workerOptions);
        worker.registerWorkflowImplementationTypes(HelloWorldWorkflowImpl.class);

        // Record worker starts
        Instrumentation.recordWorkerStart("WorkflowWorker");
        this.shutdownLatch = new CountDownLatch(1);
    }

    public void start() {
        try {
            factory.start();
            System.out.println("Worker started for task queue: " + TemporalConfig.getTaskQueue());
            System.out.println("Metrics and traces are being exported to SigNoz");
            
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (!isShuttingDown) {
                    System.out.println("Shutting down worker...");
                    close();
                    shutdownLatch.countDown();
                }
            }));

            shutdownLatch.await();
        } catch (Exception e) {
            System.err.println("Error in worker: " + e.getMessage());
            close();
        }
    }

    @Override
    public void close() {
        if (isShuttingDown) {
            return;
        }
        isShuttingDown = true;

        try {
            System.out.println("Initiating graceful shutdown...");
            
            factory.shutdown();
            factory.awaitTermination(3, TimeUnit.SECONDS);
            
            System.out.println("Shutting down gRPC connections...");
            TemporalConfig.getService().shutdown();
            
            if (!factory.isShutdown()) {
                System.out.println("Force shutting down worker factory...");
                factory.shutdownNow();
                factory.awaitTermination(2, TimeUnit.SECONDS);
            }

            System.out.println("Force shutting down service...");
            TemporalConfig.getService().shutdownNow();
            
            System.out.println("Shutting down OpenTelemetry...");
            Instrumentation.getMetricsScope().close();
            
            Thread.sleep(1000);
            
            // Shutdown OpenTelemetry
            Instrumentation.shutdownTracing();
            Instrumentation.shutdownMetrics();
            
            System.out.println("Worker shutdown completed");
        } catch (Exception e) {
            System.err.println("Error during shutdown: " + e.getMessage());
            try {
                factory.shutdownNow();
                TemporalConfig.getService().shutdownNow();
            } catch (Exception ignored) {
            }
        }
    }

    public static void main(String[] args) {
        try (HelloWorldWorker worker = new HelloWorldWorker()) {
            worker.start();
        }
    }
} 