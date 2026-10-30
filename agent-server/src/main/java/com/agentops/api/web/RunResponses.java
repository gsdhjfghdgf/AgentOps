package com.agentops.api.web;

import com.agentops.api.RunApplicationService.RunView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The REST and incremental synchronization endpoints share one run representation. */
final class RunResponses {
    private RunResponses() {}

    static Map<String,Object> run(RunView view, List<String> options) {
        var result = new LinkedHashMap<String,Object>();
        result.put("runId", view.runId()); result.put("sessionId", view.sessionId());
        result.put("status", view.status()); result.put("phase", view.phase());
        result.put("mode", view.mode()); result.put("planRunId", view.planRunId());
        result.put("questionId", view.questionId()); result.put("questionText", view.questionText());
        result.put("questionOptions", options); result.put("reasonCode", view.reasonCode());
        result.put("tokenUsed", view.tokenUsed());
        result.put("createdAt", view.createdAt() == null ? "" : view.createdAt().toString());
        result.put("finishedAt", view.finishedAt() == null ? "" : view.finishedAt().toString());
        return result;
    }
}
