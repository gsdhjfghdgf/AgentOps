package com.agentops.operations;

import com.agentops.infra.Json;
import com.agentops.persistence.*;
import com.agentops.domain.AgentDomain.MessageRole;
import com.agentops.api.RunApplicationService;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.agentops.TestRuns.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TaskProgressServiceTest {
    final JdbcTemplate db=mock(JdbcTemplate.class);final RunRepo runs=mock(RunRepo.class);final Json json=new Json();
    final TaskProgressService service=new TaskProgressService(db,runs,json);
    @Test void notesCompactOnlyCoveredReadsWithoutChangingStoredHistory(){
        when(db.queryForList(contains("notes_until_seq"),eq(Long.class),eq(OWNER.tenantId()),eq(OWNER.userId()),eq(RUN))).thenReturn(List.of(5L));
        when(db.queryForList(contains("SELECT call_id"),eq(String.class),eq(OWNER.tenantId()),eq(OWNER.userId()),eq(RUN))).thenReturn(List.of("read1","read2"));
        String payload=json.write(Map.of("content",json.write(Map.of("path","code.txt","sha256","abc","content","FULL_SOURCE_123","offset",0))));
        var old=new MessageRepo.MessageRow(OWNER.tenantId(),OWNER.userId(),SESSION,3,RUN,MessageRole.TOOL,payload,1,null,"read1",NOW);
        var newer=new MessageRepo.MessageRow(OWNER.tenantId(),OWNER.userId(),SESSION,7,RUN,MessageRole.TOOL,payload,1,null,"read2",NOW);
        var other=new MessageRepo.MessageRow(OWNER.tenantId(),OWNER.userId(),SESSION,4,RUN,MessageRole.TOOL,payload,1,null,"calculator",NOW);
        var output=service.compactReads(OWNER,RUN,List.of(old,newer,other));
        assertThat(output.get(0).payloadJson()).doesNotContain("FULL_SOURCE_123").contains("code.txt","abc");
        assertThat(output.get(1)).isEqualTo(newer);assertThat(output.get(2)).isEqualTo(other);
        assertThat(old.payloadJson()).contains("FULL_SOURCE_123");
    }
    @Test void readsWithoutSavedNotesKeepTheirExactSource(){
        var row=new MessageRepo.MessageRow(OWNER.tenantId(),OWNER.userId(),SESSION,3,RUN,MessageRole.TOOL,"source",1,null,"read1",NOW);
        assertThat(service.compactReads(OWNER,RUN,List.of(row))).containsExactly(row);
    }
    @Test void repeatNotificationDoesNotDiscardTheReturnedSourceOrHash(){
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(rowWithConfig("{}")));
        when(db.queryForObject(contains("INSERT INTO task_read_pages"),eq(Integer.class),eq(OWNER.tenantId()),eq(OWNER.userId()),eq(RUN),eq("code.txt"),eq("sha"),eq(0),eq(10),eq(10))).thenReturn(2);
        var page=Map.<String,Object>of("sha256","sha","offset",0,"nextOffset",10,"totalChars",10,"content","0123456789","eof",true);
        var result=service.observe(OWNER,RUN,"code.txt",0,page);
        assertThat(result).containsEntry("content","0123456789").containsEntry("sha256","sha").containsEntry("readCount",2).containsKey("notice");
        assertThat(page).doesNotContainKey("notice");
    }
    @Test void outdatedNotesCannotOverwriteNewerFindings(){
        when(runs.findByIdOwner(OWNER,RUN)).thenReturn(Optional.of(rowWithConfig("{}")));
        assertThatThrownBy(()->service.saveNotes(OWNER,RUN,2,"发现与证据")).isInstanceOf(RunApplicationService.ConflictException.class);
    }
    @Test void anotherOwnersRunIsRejectedBeforeReadingProgress(){
        assertThatThrownBy(()->service.snapshot(OWNER,RUN)).isInstanceOf(RunApplicationService.NotFoundException.class);
        verifyNoInteractions(db);
    }
}
