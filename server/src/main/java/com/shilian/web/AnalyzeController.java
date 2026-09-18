package com.shilian.web;

import com.shilian.analyze.AnalyzeService;
import com.shilian.domain.AnalyzeOutcome;
import com.shilian.infrastructure.resilience.AnalysisGate;
import com.shilian.web.dto.AnalyzeRequest;
import com.shilian.web.dto.AnalyzeResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分析接口。
 *
 * <p>刻意做成「分析」和「保存」两个接口，而不是一个接口直接入库。
 * 因为 AI 的判断天然有偏差，直接入库等于把不确定性留给用户事后发现并修正；
 * 而让他存之前扫一眼、改一个下拉框，成本几乎为零。
 */
@RestController
@RequestMapping("/api")
public class AnalyzeController {

    private final AnalyzeService analyzeService;
    private final DraftStore draftStore;
    private final AnalysisGate gate;

    public AnalyzeController(AnalyzeService analyzeService, DraftStore draftStore, AnalysisGate gate) {
        this.analyzeService = analyzeService;
        this.draftStore = draftStore;
        this.gate = gate;
    }

    /**
     * 名额必须在 finally 里还回去。
     *
     * <p>分析会抛异常（网址打不开、AI 失败、熔断），如果名额只在正常路径释放，
     * 失败几次之后闸门就永久占满，之后每个请求都是 429——
     * 表现为「用着用着突然一直说忙，重启才好」，极难往回查。
     */
    @PostMapping("/analyze")
    public AnalyzeResponse analyze(@Valid @RequestBody AnalyzeRequest request) {
        gate.acquire();
        try {
            AnalyzeOutcome outcome = analyzeService.analyze(request.url(), request.text());
            String draftId = draftStore.put(outcome);
            return new AnalyzeResponse(draftId, outcome.draft());
        } finally {
            gate.release();
        }
    }
}
