package helloworld.workflows;

import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface HelloWorldWorkflow {
    @WorkflowMethod
    String sayHello(String name);
}