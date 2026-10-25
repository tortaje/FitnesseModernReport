package com.tortaje.fitnesse.plugins;

import fitnesse.responders.ResponderFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExecutionReportPluginTest {

    @Test
    void registerResponders_registersExecutionReport() throws Exception {
        ResponderFactory factory = new ResponderFactory(".");
        new ExecutionReportPlugin().registerResponders(factory);
        assertEquals(ExecutionReportResponder.class, factory.getResponderClass("ExecutionReport"));
    }

    @Test
    void plugin_isDiscoverableThroughServiceLoader_withoutPluginsProperties() {
        boolean found = java.util.ServiceLoader.load(fitnesse.plugins.PluginFeatureFactory.class)
                .stream().anyMatch(p -> p.type() == ExecutionReportPlugin.class);
        org.junit.jupiter.api.Assertions.assertTrue(found, "ExecutionReportPlugin must be declared in META-INF/services");
    }
}
