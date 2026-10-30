package com.agentops.model;

import com.agentops.domain.ModelDecision;
import com.agentops.domain.Primitives.RunSnapshot;
import com.agentops.domain.ToolTypes.ToolSpec;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Consumer;

@Component
@Primary
public class ModelRouter implements ModelPort {
    @Override public Response request(RunSnapshot snapshot,String system,List<ModelMessage> messages,List<ToolSpec> tools,ExecutionControl control,Consumer<String> provisional,Consumer<String> commentary){
        return (settings.forRun(snapshot).isMock()?mock:remote).request(snapshot,system,messages,tools,control,provisional,commentary);
    }
    private final ModelSettingsService settings;
    private final MockModelPort mock;
    private final OpenAiModelPort remote;
    public ModelRouter(ModelSettingsService settings,MockModelPort mock,OpenAiModelPort remote) {
        this.settings=settings;this.mock=mock;this.remote=remote;
    }
    @Override public ModelDecision next(RunSnapshot snapshot,String system,List<ModelMessage> messages,
                                        List<ToolSpec> tools,ExecutionControl control,Consumer<String> provisional) {
        return request(snapshot,system,messages,tools,control,provisional).decision();
    }
    @Override public Response request(RunSnapshot snapshot,String system,List<ModelMessage> messages,
                                      List<ToolSpec> tools,ExecutionControl control,Consumer<String> provisional) {
        return (settings.forRun(snapshot).isMock()?mock:remote).request(snapshot,system,messages,tools,control,provisional);
    }
}
