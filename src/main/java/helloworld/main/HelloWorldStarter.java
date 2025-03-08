package helloworld.main;

import helloworld.config.TemporalConfig;
import helloworld.config.Instrumentation;
import helloworld.workflows.HelloWorldWorkflow;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;

/**
 * Application entry point for starting Hello World workflows with telemetry.
 */
public class HelloWorldStarter {
    private final WorkflowServiceStubs workflowServiceStubs;
    private final WorkflowClient workflowClient;
    private final LongCounter workflowCompletionCounter;
    private final LongCounter workflowStartCounter;

    public HelloWorldStarter() {
        Instrumentation.initializeTelemetry();

        Meter meter = Instrumentation.getMeter();
        workflowCompletionCounter = meter
            .counterBuilder("workflow_completed_count_total")
            .setDescription("Total number of workflow executions completed")
            .setUnit("1")
            .build();
            
        workflowStartCounter = meter
            .counterBuilder("workflow_started_count_total")
            .setDescription("Total number of workflow executions started")
            .setUnit("1")
            .build();

        WorkflowServiceStubsOptions stubOptions = WorkflowServiceStubsOptions.newBuilder()
            .setMetricsScope(Instrumentation.getMetricsScope())
            .build();

        WorkflowClientOptions clientOptions = WorkflowClientOptions.newBuilder()
            .setInterceptors(Instrumentation.getClientInterceptor())
            .build();

        this.workflowServiceStubs = TemporalConfig.getService();
        this.workflowClient = TemporalConfig.getWorkflowClient(stubOptions, clientOptions);
    }

    public void runWorkflow(String name) {
        Tracer tracer = Instrumentation.getTracer();
        
        Span parentSpan = tracer.spanBuilder("StartWorkflow")
            .setAttribute("workflow.type", "HelloWorld")
            .setAttribute("workflow.name", name)
            .startSpan();
        
        try (Scope scope = parentSpan.makeCurrent()) {
            String workflowId = "hello-world-" + UUID.randomUUID().toString();
            
            WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalConfig.getTaskQueue())
                .setWorkflowId(workflowId)
                .build();
            
            HelloWorldWorkflow workflow = workflowClient.newWorkflowStub(
                HelloWorldWorkflow.class,
                options
            );
            
            workflowStartCounter.add(1L);
            parentSpan.setAttribute("workflow.started", true);
            
            Span executeSpan = tracer.spanBuilder("ExecuteWorkflow")
                .setParent(io.opentelemetry.context.Context.current().with(parentSpan))
                .setAttribute("workflow.id", workflowId)
                .setAttribute("workflow.type", "temporal")
                .setAttribute("service.name", Instrumentation.getServiceName())
                .setAttribute("workflow.task_queue", TemporalConfig.getTaskQueue())
                .startSpan();
            
            String result;
            try (Scope executeScope = executeSpan.makeCurrent()) {
                result = workflow.sayHello(name);
                executeSpan.setAttribute("workflow.result", result);
                executeSpan.setStatus(StatusCode.OK);
                workflowCompletionCounter.add(1L);
                parentSpan.setAttribute("workflow.completed", true);
                
                Instrumentation.recordSuccess(
                    "HelloWorldWorkflow", 
                    workflowId, 
                    "run-" + UUID.randomUUID().toString(),
                    TemporalConfig.getNamespace()
                );
            } catch (Exception e) {
                executeSpan.recordException(e);
                executeSpan.setStatus(StatusCode.ERROR);
                
                Instrumentation.recordFailure(
                    "HelloWorldWorkflow", 
                    workflowId, 
                    "run-" + UUID.randomUUID().toString(),
                    TemporalConfig.getNamespace()
                );
                
                throw e;
            } finally {
                executeSpan.end();
            }
            
            System.out.println("Workflow execution completed:");
            System.out.println("Result: " + result);
            System.out.println("Workflow ID: " + workflowId);
            System.out.println("Metrics and traces are being exported to SigNoz");
            
            parentSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            System.err.println("Error executing workflow: " + e.getMessage());
            parentSpan.recordException(e);
            parentSpan.setStatus(StatusCode.ERROR);
            throw new RuntimeException("Failed to execute workflow", e);
        } finally {
            parentSpan.end();
            
            try {
                Instrumentation.shutdownMetrics();
                Instrumentation.shutdownTracing();
                
                if (workflowClient != null) {
                    workflowServiceStubs.shutdown();
                    workflowServiceStubs.awaitTermination(5, TimeUnit.SECONDS);
                }
                
                Thread.sleep(1000);
                System.exit(0);
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
                System.exit(1);
            }
        }
    }

    public static void main(String[] args) {
        try {
            HelloWorldStarter starter = new HelloWorldStarter();
            String name = args.length > 0 ? args[0] : "Temporal";
            starter.runWorkflow(name);
        } catch (Exception e) {
            System.err.println("Application error: " + e.getMessage());
            System.exit(1);
        }
    }
}