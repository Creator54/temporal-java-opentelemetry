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

/**
 * Application entry point for starting Hello World workflows with telemetry.
 */
public class HelloWorldStarter {
    private final WorkflowServiceStubs workflowServiceStubs;
    private final WorkflowClient workflowClient;

    public HelloWorldStarter() {
        Instrumentation.initializeTelemetry();

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
        String workflowId = "hello-world-" + UUID.randomUUID();
        String runId = "run-" + UUID.randomUUID();
        String namespace = TemporalConfig.getNamespace();
        
        Span parentSpan = tracer.spanBuilder("StartWorkflow")
            .setAttribute("workflow.type", "HelloWorld")
            .setAttribute("workflow.name", name)
            .startSpan();
        
        try (Scope scope = parentSpan.makeCurrent()) {
            // Create workflow options and client
            WorkflowOptions options = WorkflowOptions.newBuilder()
                .setTaskQueue(TemporalConfig.getTaskQueue())
                .setWorkflowId(workflowId)
                .build();
            
            HelloWorldWorkflow workflow = workflowClient.newWorkflowStub(
                HelloWorldWorkflow.class, options);
            
            // Record workflow start
            Instrumentation.recordMetric("workflow_started_count_total", 
                Instrumentation.createWorkflowAttributes(
                    "HelloWorldWorkflow", workflowId, runId, namespace));
            parentSpan.setAttribute("workflow.started", true);
            
            // Execute workflow with tracing
            executeWorkflow(workflow, name, workflowId, runId, namespace, parentSpan);
            
            parentSpan.setStatus(StatusCode.OK);
        } catch (Exception e) {
            System.err.println("Error executing workflow: " + e.getMessage());
            parentSpan.recordException(e);
            parentSpan.setStatus(StatusCode.ERROR);
            throw new RuntimeException("Failed to execute workflow", e);
        } finally {
            parentSpan.end();
            shutdown();
        }
    }
    
    private String executeWorkflow(HelloWorldWorkflow workflow, String name, 
                                   String workflowId, String runId, String namespace, Span parentSpan) {
        Tracer tracer = Instrumentation.getTracer();
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
            
            // Record workflow completion
            Instrumentation.recordMetric("workflow_completed_count_total", 
                Instrumentation.createWorkflowAttributes(
                    "HelloWorldWorkflow", workflowId, runId, namespace));
            parentSpan.setAttribute("workflow.completed", true);
            
            Instrumentation.recordSuccess("HelloWorldWorkflow", workflowId, runId, namespace);
            
            System.out.println("Workflow execution completed:");
            System.out.println("Result: " + result);
            System.out.println("Workflow ID: " + workflowId);
            System.out.println("Metrics and traces are being exported to SigNoz");
            
            return result;
        } catch (Exception e) {
            executeSpan.recordException(e);
            executeSpan.setStatus(StatusCode.ERROR);
            Instrumentation.recordFailure("HelloWorldWorkflow", workflowId, runId, namespace);
            throw e;
        } finally {
            executeSpan.end();
        }
    }
    
    private void shutdown() {
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

    public static void main(String[] args) {
        try {
            HelloWorldStarter starter = new HelloWorldStarter();
            starter.runWorkflow(args.length > 0 ? args[0] : "Temporal");
        } catch (Exception e) {
            System.err.println("Application error: " + e.getMessage());
            System.exit(1);
        }
    }
}