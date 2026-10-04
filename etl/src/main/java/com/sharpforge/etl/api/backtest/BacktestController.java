package com.sharpforge.etl.api.backtest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/backtest")
@RequiredArgsConstructor
@Slf4j
public class BacktestController {

    private final BacktestService backtestService;

    /**
     * Executes a dynamic backtest query using deep situational filters.
     * @param request The JSON payload containing nested filter thresholds
     * @return Aggregated performance summary and paginated historical game logs
     */
    @PostMapping("/run")
    public ResponseEntity<BacktestResponseDto> runBacktest(@RequestBody BacktestRequestDto request) {
        log.info("Received backtest request for bet target: {}", request.getBetTarget());
        
        BacktestResponseDto response = backtestService.runBacktest(request);
        
        return ResponseEntity.ok(response);
    }
}
