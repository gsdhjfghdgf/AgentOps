package com.agentops.context;

import com.agentops.model.ModelPort.ModelMessage;
import com.agentops.model.ModelPort.ToolCall;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class ContextTranscriptTest {
    @Test void failedRunDanglingCallGetsUnknownResultBeforeNewRequest() {
        var call=ModelMessage.assistantWithCalls("",List.of(new ToolCall("c1","send_email","{}")));
        var next=ModelMessage.user("继续查询");
        var result=ContextAssembler.completeToolTranscript(List.of(call,next));
        assertThat(result).hasSize(3);
        assertThat(result.get(1).toolCallId()).isEqualTo("c1");
        assertThat(result.get(1).content()).contains("没有已确认的结果","不要自动重试外部写操作");
        assertThat(result.get(2)).isEqualTo(next);
    }
    @Test void confirmedResultPreservedAndClippedOrphanRemoved() {
        var call=ModelMessage.assistantWithCalls("",List.of(new ToolCall("c1","current_time","{}")));
        var result=ModelMessage.toolResult("c1","confirmed");
        assertThat(ContextAssembler.completeToolTranscript(List.of(ModelMessage.toolResult("orphan","old"),call,result)))
                .containsExactly(call,result);
    }
}
