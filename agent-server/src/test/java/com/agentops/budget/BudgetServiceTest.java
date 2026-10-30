package com.agentops.budget;

import com.agentops.config.AgentProperties;
import com.agentops.persistence.BudgetRepo;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.UUID;
import static com.agentops.TestRuns.*;
import static com.agentops.domain.AgentDomain.StepKind.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BudgetServiceTest {
    private final BudgetRepo repo=mock(BudgetRepo.class);
    private final AgentProperties props=new AgentProperties(null,
            new AgentProperties.Budget(8,16,40000,2000,0,"USD",10),null,null,null,null,null,null);
    private final BudgetService service=new BudgetService(repo,props,Clock.systemUTC());

    @Test void lastAllowedModelCallCanStillExecuteItsApprovedTool() {
        when(repo.reserve(any(),any(),anyString(),anyString(),anyLong(),anyLong())).thenReturn(true);
        assertThat(service.reserve(OWNER,SESSION,RUN,UUID.randomUUID(),new BudgetService.StepAttempts(8,5),500,0,TOOL)).isTrue();
        assertThat(service.reserve(OWNER,SESSION,RUN,UUID.randomUUID(),new BudgetService.StepAttempts(8,5),500,0,MODEL)).isFalse();
    }
    @Test void finalModelAnswerIsAllowedWhenToolLimitReached() {
        when(repo.reserve(any(),any(),anyString(),anyString(),anyLong(),anyLong())).thenReturn(true);
        assertThat(service.reserve(OWNER,SESSION,RUN,UUID.randomUUID(),new BudgetService.StepAttempts(7,16),500,0,MODEL)).isTrue();
        assertThat(service.reserve(OWNER,SESSION,RUN,UUID.randomUUID(),new BudgetService.StepAttempts(7,16),500,0,TOOL)).isFalse();
    }
    @Test void tokenBudgetStillAppliesToApprovedTool() {
        assertThat(service.reserve(OWNER,SESSION,RUN,UUID.randomUUID(),new BudgetService.StepAttempts(1,1),500,0,TOOL)).isFalse();
        verify(repo,never()).insertReservation(any(),any(),any(),any(),any(),anyString(),anyLong(),anyLong(),anyString());
    }
    @Test void extendedAccountLimitsApplyInsteadOfOriginalDefaults() {
        when(repo.findAccount(OWNER.tenantId(),OWNER.userId(),service.runPeriodKey(RUN),"run"))
                .thenReturn(java.util.Optional.of(new BudgetRepo.AccountRow(OWNER.tenantId(),OWNER.userId(),
                        service.runPeriodKey(RUN),"run",1000000,0,136,528,0,0)));
        when(repo.reserve(any(),any(),anyString(),anyString(),anyLong(),anyLong())).thenReturn(true);
        assertThat(service.reserve(OWNER,SESSION,RUN,UUID.randomUUID(),new BudgetService.StepAttempts(9,17),500,0,MODEL)).isTrue();
    }
}
