package com.sharpforge.etl.api.backtest;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/trendybot")
@RequiredArgsConstructor
@Slf4j
public class TrendyBotController {

    private final TrendyBotParser trendyBotParser;

    @Data
    public static class TrendyBotRequest {
        private String prompt;
    }

    /**
     * Accepts a plain-language prompt and returns the parsed BacktestRequestDto filter criteria.
     * The frontend can then update its UI state to match the returned JSON and auto-trigger the backtest.
     */
    @PostMapping("/parse")
    public ResponseEntity<BacktestRequestDto> parsePrompt(@RequestBody TrendyBotRequest request) {
        log.info("TrendyBot received prompt: {}", request.getPrompt());
        BacktestRequestDto filters = trendyBotParser.parseQuery(request.getPrompt());
        return ResponseEntity.ok(filters);
    }
}
