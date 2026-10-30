package com.agentops.model;

import com.agentops.domain.AgentDomain;
import com.agentops.domain.Primitives;
import com.agentops.domain.ResultTypes;
import com.agentops.validation.ResultValidator;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class RealAnswerValidationTest {
    private Primitives.RunSnapshot run(Map<String,Object> requirements,List<String> kinds) {
        var key=new Primitives.RunKey(com.agentops.TestRuns.OWNER,com.agentops.TestRuns.SESSION,com.agentops.TestRuns.RUN);
        return new Primitives.RunSnapshot(key,AgentDomain.RunStatus.RUNNING,AgentDomain.Phase.VALIDATE,
                1,1,1,Instant.MAX,null,new Primitives.CompletionContract(1,"generic",requirements,kinds,false,""),
                null,null,null,null,0);
    }
    @Test void validShortAnswerDoesNotTriggerRepeatedModelCalls() {
        var result=new ResultValidator().validate(run(Map.of(),List.of()),
                ResultTypes.CandidateAnswer.of(UUID.randomUUID().toString(),"4"));
        assertThat(result.verdict()).isEqualTo(AgentDomain.ValidationVerdict.PASS);
    }
    @Test void explicitMinimumStillApplies() {
        var result=new ResultValidator().validate(run(Map.of("minAnswerChars",20),List.of()),
                ResultTypes.CandidateAnswer.of(UUID.randomUUID().toString(),"4"));
        assertThat(result.errorCodes()).contains("ANSWER_TOO_SHORT");
    }
    @Test void claimingAFileWasGeneratedDoesNotSubstituteForVerifiedArtifact() {
        var result=new ResultValidator().validate(run(Map.of(),List.of("output")),
                ResultTypes.CandidateAnswer.of(UUID.randomUUID().toString(),"文件已生成，对象ID为某个不存在的对象。"));
        assertThat(result.errorCodes()).contains("ARTIFACT_REQUIRED_BUT_MISSING");
    }
}
