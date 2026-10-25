package com.tortaje.fitnesse.plugins;

import fitnesse.plugins.PluginException;
import fitnesse.plugins.PluginFeatureFactory;
import fitnesse.responders.ResponderFactory;

public class ExecutionReportPlugin implements PluginFeatureFactory {

    public static final String RESPONDER_NAME = "ExecutionReport";

    @Override
    public void registerResponders(ResponderFactory factory) throws PluginException {
        factory.addResponder(RESPONDER_NAME, ExecutionReportResponder.class);
    }
}
