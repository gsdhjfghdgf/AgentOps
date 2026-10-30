package com.agentops.memory;

import com.agentops.domain.Primitives.Principal;
import com.agentops.infra.Json;
import com.agentops.model.EmbeddingRouter;
import com.agentops.persistence.MemoryRepo;
import com.agentops.persistence.MemoryRepo.MemoryRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 长期记忆服务（文档 6 节）：
 * 写入链路：对话 → 信息提取 → 价值评估 → 分类 → 去重冲突判断 → 写入；
 * 召回链路：先按 tenant/user/scope/权限筛选，再相关性排序，按 token 预算选入少量结果。
 *
 * 稳定偏好、重要决策、可复用知识值得保存；寒暄与确认不进入长期记忆。
 */
@Service
public class MemoryService {

    private static final Logger log = LoggerFactory.getLogger(MemoryService.class);

    private final MemoryRepo repo;
    private final EmbeddingRouter embedding;
    private final Json json;
    private final MemorySummarizer summarizer;

    public MemoryService(MemoryRepo repo, EmbeddingRouter embedding, Json json,MemorySummarizer summarizer) {
        this.repo = repo;
        this.embedding = embedding;
        this.json = json;
        this.summarizer=summarizer;
    }

    /** 显式保存（同步 API，立即跨会话生效，文档 6.4）。 */
    public Map<String, Object> saveExplicit(Principal owner, String scopeKey, String memoryKey,
                                            String kind, String content, Duration validFor) {
        UUID id = repo.upsert(owner.tenantId(), owner.userId(), scopeKey, memoryKey, kind,
                content, "explicit", 0.8f, validFor == null ? null : Instant.now().plus(validFor));
        long version = repo.memoryVersion(owner.tenantId(), owner.userId());
        writeEmbeddingAsync(owner, id, content);
        log.info("显式记忆已保存 user={} key={} kind={}", owner.userId(), memoryKey, kind);
        return Map.of("memoryId", id.toString(), "memoryVersion", version, "authority", "explicit");
    }

    /**
     * 从对话自动提取（Outbox 消费，最终一致，文档 6.4）。
     * 模型总结或演示规则提取；仅接受带有用户原话依据的候选记忆。
     */
    public int extractFromRun(Principal owner,UUID sessionId,List<MemorySummarizer.Source> sources) {
        if(!repo.autoEnabled(owner.tenantId(),owner.userId()) || sources.isEmpty())return 0;
        var bySeq=new java.util.HashMap<Long,MemorySummarizer.Source>();
        sources.forEach(source->bySeq.put(source.seq(),source));
        int count=0;
        for(var candidate:summarizer.summarize(owner,sources)) {
            var source=bySeq.get(candidate.sourceSeq());
            if(source==null || candidate.evidence()==null || candidate.evidence().isBlank()
                    || !source.text().contains(candidate.evidence()) || candidate.content()==null
                    || candidate.content().isBlank() || candidate.content().length()>500
                    || !candidate.key().matches("[a-zA-Z0-9_.-]{1,80}")
                    || !java.util.Set.of("preference","fact","experience","episode").contains(candidate.kind()))continue;
            String combined=source.text()+" "+candidate.content();
            if(Pattern.compile("(?i)密码|密钥|口令|password|api[ _-]?key|access[ _-]?token|bearer\\s|secret").matcher(combined).find())continue;
            var saved=repo.upsertAutomatic(owner.tenantId(),owner.userId(),"auto:"+candidate.key(),candidate.kind(),
                    candidate.content().strip(),source.at());
            if(saved.isPresent()) {
                UUID id=saved.get();
                long version=repo.findVersionById(owner.tenantId(),id).orElseThrow();
                repo.insertSource(owner.tenantId(),owner.userId(),id,version,sessionId,source.seq(),candidate.evidence());
                writeEmbeddingSync(owner,id,candidate.content().strip());
                count++;
            }
        }
        if(count>0)repo.memoryVersion(owner.tenantId(),owner.userId());
        return count;
    }

    public Map<String,Object> settings(Principal owner) {
        return Map.of("enabled",repo.autoEnabled(owner.tenantId(),owner.userId()),
                "mode",summarizer.modelMode(owner)?"model":"rules");
    }
    public void setAutoEnabled(Principal owner,boolean enabled){repo.setAutoEnabled(owner.tenantId(),owner.userId(),enabled);}
    public List<Map<String,Object>> list(Principal owner,int limit) {
        var evidence=repo.latestEvidence(owner.tenantId(),owner.userId());
        return repo.listActive(owner.tenantId(),owner.userId(),limit).stream().map(row->{
            var item=new LinkedHashMap<String,Object>();
            item.put("memoryId",row.id().toString()); item.put("memoryKey",row.memoryKey());
            item.put("kind",row.kind()); item.put("content",row.content());item.put("authority",row.authority());
            item.put("version",row.version()); item.put("automatic",row.memoryKey().startsWith("auto:"));
            item.put("evidence",evidence.getOrDefault(row.id(),""));
            return (Map<String,Object>)item;
        }).toList();
    }

    /** 召回（文档 6.4）：结合时效与重要性，去重后按预算选入 3~8 条。 */
    public List<Map<String, Object>> recall(Principal owner, String scopeKey, String query, int limit) {
        if (limit <= 0 || repo.listActive(owner.tenantId(), owner.userId(), 1).isEmpty()) return List.of();
        String model = embedding.modelId();
        var vectorLiteral = embedding.toLiteral(embedding.embed(query == null ? "" : query));
        var rows = repo.recall(owner.tenantId(), owner.userId(), scopeKey == null ? "user" : scopeKey,
                vectorLiteral, model, limit);

        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var item = new LinkedHashMap<String, Object>();
            item.put("memoryKey", row.memoryKey());
            item.put("kind", row.kind());
            item.put("content", row.content());
            item.put("authority", row.authority());
            item.put("importance", row.importance());
            result.add(item);
        }
        return result;
    }

    /** 相对稳定类记忆：按键直接查询（文档 6.2）。 */
    public java.util.Optional<MemoryRow> findPreference(Principal owner, String key) {
        return repo.findActiveByKey(owner.tenantId(), owner.userId(), "user", key);
    }

    /** 撤回（文档 6.3：保留逻辑撤回标记，拒绝更早来源的旧事件重新写入）。 */
    public void revoke(Principal owner, String scopeKey, String memoryKey) {
        repo.revoke(owner.tenantId(), owner.userId(), scopeKey, memoryKey);
        repo.memoryVersion(owner.tenantId(), owner.userId());
    }

    /** 删除用户数据时清理正文、向量与来源（文档 6.3）。 */
    public void hardRevokeById(Principal owner, UUID memoryId) {
        repo.revokeById(owner.tenantId(),owner.userId(), memoryId);
        repo.memoryVersion(owner.tenantId(), owner.userId());
    }

    public long memoryVersion(Principal owner) {
        return repo.memoryVersion(owner.tenantId(), owner.userId());
    }

    private void writeEmbeddingAsync(Principal owner, UUID memoryId, String content) {
        writeEmbeddingSync(owner, memoryId, content);
    }

    /**
     * 写回向量：仅当期望 version 仍一致时写入（文档 6.3）。
     * 这里同步获取当前 version 后写入；异步实现需保存期望 version 再校验。
     */
    private void writeEmbeddingSync(Principal owner, UUID memoryId, String content) {
        try {
            long version = repo.findVersionById(owner.tenantId(), memoryId).orElse(1L);
            String literal = embedding.toLiteral(embedding.embed(content));
            boolean ok = repo.writeEmbedding(owner.tenantId(), memoryId, version, literal, embedding.modelId(),content);
            if (!ok) {
                log.debug("向量写回时版本已变化，放弃（防止旧 embedding 恢复）memory={}", memoryId);
            }
        } catch (RuntimeException e) {
            log.warn("向量写回失败 memory={} err={}", memoryId, e.getMessage());
        }
    }

    private String sha1Key(String input) {
        return Integer.toHexString(input.hashCode());
    }
}
