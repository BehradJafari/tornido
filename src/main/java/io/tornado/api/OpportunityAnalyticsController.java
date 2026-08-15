package io.tornado.api;

import io.tornado.reporting.OpportunityAnalyticsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/reports/opportunities")
public class OpportunityAnalyticsController {
    private final OpportunityAnalyticsService analytics;

    public OpportunityAnalyticsController(OpportunityAnalyticsService analytics) {
        this.analytics = analytics;
    }

    @GetMapping("/summary")
    OpportunityAnalyticsService.Dashboard summary(@ModelAttribute OpportunityQuery query) {
        return analytics.dashboard(query.filter());
    }

    @GetMapping
    OpportunityAnalyticsService.Page opportunities(
            @ModelAttribute OpportunityQuery query,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return analytics.list(query.filter(), page, size);
    }

    @GetMapping("/{id}")
    OpportunityAnalyticsService.Detail detail(
            @PathVariable long id,
            @RequestParam(defaultValue = "2") int tpLevel,
            @RequestParam(defaultValue = "1") int slLevel) {
        return analytics.detail(id, tpLevel, slLevel);
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    ResponseEntity<byte[]> export(@ModelAttribute OpportunityQuery query) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=opportunities.csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(analytics.csv(query.filter()));
    }

    public static class OpportunityQuery {
        public String coin;
        public String pair;
        public Long horizon;
        public String direction;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) public Instant from;
        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) public Instant to;
        public int tpLevel = 2;
        public int slLevel = 1;
        public Double minimumHistoricalWinRate;
        public Double maximumHistoricalWinRate;
        public Integer mixSize;
        public Integer mixRank;
        public String strategyCode;
        public String method;
        public Boolean notificationEligible;
        public Boolean telegramSent;
        public String outcome;
        public Integer signalVersion = 3;

        public String getCoin(){return coin;} public void setCoin(String v){coin=v;}
        public String getPair(){return pair;} public void setPair(String v){pair=v;}
        public Long getHorizon(){return horizon;} public void setHorizon(Long v){horizon=v;}
        public String getDirection(){return direction;} public void setDirection(String v){direction=v;}
        public Instant getFrom(){return from;} public void setFrom(Instant v){from=v;}
        public Instant getTo(){return to;} public void setTo(Instant v){to=v;}
        public int getTpLevel(){return tpLevel;} public void setTpLevel(int v){tpLevel=v;}
        public int getSlLevel(){return slLevel;} public void setSlLevel(int v){slLevel=v;}
        public Double getMinimumHistoricalWinRate(){return minimumHistoricalWinRate;} public void setMinimumHistoricalWinRate(Double v){minimumHistoricalWinRate=v;}
        public Double getMaximumHistoricalWinRate(){return maximumHistoricalWinRate;} public void setMaximumHistoricalWinRate(Double v){maximumHistoricalWinRate=v;}
        public Integer getMixSize(){return mixSize;} public void setMixSize(Integer v){mixSize=v;}
        public Integer getMixRank(){return mixRank;} public void setMixRank(Integer v){mixRank=v;}
        public String getStrategyCode(){return strategyCode;} public void setStrategyCode(String v){strategyCode=v;}
        public String getMethod(){return method;} public void setMethod(String v){method=v;}
        public Boolean getNotificationEligible(){return notificationEligible;} public void setNotificationEligible(Boolean v){notificationEligible=v;}
        public Boolean getTelegramSent(){return telegramSent;} public void setTelegramSent(Boolean v){telegramSent=v;}
        public String getOutcome(){return outcome;} public void setOutcome(String v){outcome=v;}
        public Integer getSignalVersion(){return signalVersion;} public void setSignalVersion(Integer v){signalVersion=v;}

        OpportunityAnalyticsService.Filter filter() {
            return new OpportunityAnalyticsService.Filter(coin, pair, horizon, direction, from, to,
                    tpLevel, slLevel, minimumHistoricalWinRate, maximumHistoricalWinRate, mixSize,
                    mixRank, strategyCode, method, notificationEligible, telegramSent, outcome, signalVersion);
        }
    }
}
