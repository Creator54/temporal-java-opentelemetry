package helloworld.workflows.impl;

import helloworld.workflows.HelloWorldWorkflow;

public class HelloWorldWorkflowImpl implements HelloWorldWorkflow {
    @Override
    public String sayHello(String name) {
        return "Hello " + name + "!";
    }
}