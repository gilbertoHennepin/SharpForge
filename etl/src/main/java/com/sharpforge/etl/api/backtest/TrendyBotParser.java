package com.sharpforge.etl.api.backtest;

import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class TrendyBotParser {

    /**
     * Parses a natural language query into a structured BacktestRequestDto.
     * Uses heuristic keyword matching and regex extraction.
     * In a production environment, this would route to an LLM (e.g., via Spring AI).
     */
    public BacktestRequestDto parseQuery(String query) {
        log.info("TrendyBot parsing query: {}", query);
        String lowerQuery = query.toLowerCase();
        
        BacktestRequestDto dto = new BacktestRequestDto();
        
        // 1. Determine League
        if (lowerQuery.contains("nfl")) dto.setLeagueId(1);
        else if (lowerQuery.contains("ncaaf") || lowerQuery.contains("college football")) dto.setLeagueId(2);
        else if (lowerQuery.contains("nba")) dto.setLeagueId(3);
        else if (lowerQuery.contains("nhl")) dto.setLeagueId(5);
        
        // 2. Bet Target & Side
        if (lowerQuery.contains("home")) {
            dto.setBetTarget(BacktestRequestDto.BetTarget.HOME_SPREAD);
        } else if (lowerQuery.contains("away") || lowerQuery.contains("road")) {
            dto.setBetTarget(BacktestRequestDto.BetTarget.AWAY_SPREAD);
        } else if (lowerQuery.contains("over")) {
            dto.setBetTarget(BacktestRequestDto.BetTarget.OVER);
        } else if (lowerQuery.contains("under")) {
            dto.setBetTarget(BacktestRequestDto.BetTarget.UNDER);
        }

        // 3. Favorite / Underdog Logic
        if (lowerQuery.contains("favorite") || lowerQuery.contains("fav")) {
            if (dto.getBetTarget() == null) dto.setBetTarget(BacktestRequestDto.BetTarget.FAVORITE);
            // If road favorite requested
            if (lowerQuery.contains("road") || lowerQuery.contains("away")) {
                BacktestRequestDto.OddsFilters odds = new BacktestRequestDto.OddsFilters();
                odds.setMinSpread(0.5); // Home spread > 0 means Home is Dog, Away is Fav
                dto.setOdds(odds);
            }
        } else if (lowerQuery.contains("underdog") || lowerQuery.contains("dog")) {
            if (dto.getBetTarget() == null) dto.setBetTarget(BacktestRequestDto.BetTarget.UNDERDOG);
        }

        // 4. Streaks (e.g., "5+ game winning streak", "3 game losing streak")
        Pattern winStreakPattern = Pattern.compile("(\\d+)\\+?\\s*game (win|winning) streak");
        Matcher winMatcher = winStreakPattern.matcher(lowerQuery);
        if (winMatcher.find()) {
            int streak = Integer.parseInt(winMatcher.group(1));
            // We map this to a fictional StreaksFilter or RestFilters in this simple demo
            // Since DTO doesn't currently have "minSuWinStreak", let's assume we map it to rest advantage for now
            // or we need to add StreaksFilters to BacktestRequestDto.
            log.info("Detected winning streak of {} games", streak);
        }
        
        Pattern lossStreakPattern = Pattern.compile("(\\d+)\\+?\\s*game (los|losing) streak");
        Matcher lossMatcher = lossStreakPattern.matcher(lowerQuery);
        if (lossMatcher.find()) {
            int streak = Integer.parseInt(lossMatcher.group(1));
            log.info("Detected losing streak of {} games", streak);
        }

        // 5. Weather
        if (lowerQuery.contains("wind")) {
            BacktestRequestDto.WeatherFilters weather = new BacktestRequestDto.WeatherFilters();
            weather.setMinWindSpeedMph(15.0); // Default to high wind if mentioned
            weather.setExcludeDomes(true);
            
            Pattern windPattern = Pattern.compile("(\\d+)\\+?\\s*mph");
            Matcher windMatcher = windPattern.matcher(lowerQuery);
            if (windMatcher.find()) {
                weather.setMinWindSpeedMph(Double.parseDouble(windMatcher.group(1)));
            }
            dto.setWeather(weather);
        }

        // 6. Public fade
        if (lowerQuery.contains("fade the public") || lowerQuery.contains("contrarian")) {
            BacktestRequestDto.PublicBettingFilters pub = new BacktestRequestDto.PublicBettingFilters();
            pub.setMaxPublicTicketPct(35.0);
            pub.setMinSharpMoneyPct(60.0);
            pub.setIsSharpVsPublic(true);
            dto.setPublicBetting(pub);
        }

        return dto;
    }
}
