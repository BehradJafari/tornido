package io.tornado.api;

import io.tornado.datafetch.LivePriceStream;
import io.tornado.datafetch.BinanceMarketDataClient;
import io.tornado.persistence.*;
import io.tornado.scheduler.PredictionService;
import io.tornado.strategies.StrategyDefinition;
import jakarta.validation.Valid; import jakarta.validation.constraints.*;
import org.springframework.format.annotation.DateTimeFormat; import org.springframework.http.*; import org.springframework.transaction.annotation.Transactional; import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import java.time.*; import java.util.*;

@RestController @RequestMapping("/api")
public class TornadoController {
 private final CoinRepository coins; private final PredictionRepository predictions; private final AnalysisRunRepository runs; private final AppSettingsRepository settings; private final PredictionService service; private final LivePriceStream prices; private final BinanceMarketDataClient market; private final ReportService reports; private final ExcelReportService excel;
 public TornadoController(CoinRepository c,PredictionRepository p,AnalysisRunRepository r,AppSettingsRepository a,PredictionService s,LivePriceStream l,BinanceMarketDataClient m,ReportService reports,ExcelReportService excel){coins=c;predictions=p;runs=r;settings=a;service=s;prices=l;market=m;this.reports=reports;this.excel=excel;}
 @GetMapping("/coins") List<Coin> coins(){return coins.findAllByActiveTrueOrderBySymbol();}
 @PostMapping("/coins") ResponseEntity<Coin> add(@Valid @RequestBody CoinRequest r){String symbol=r.symbol().toUpperCase(Locale.ROOT);String pair=r.pair().toUpperCase(Locale.ROOT);return ResponseEntity.status(201).body(coins.findBySymbolIgnoreCase(symbol).filter(Coin::isActive).orElseGet(()->coins.save(new Coin(symbol,pair))));}
 @DeleteMapping("/coins/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) @Transactional void remove(@PathVariable long id){coins.findById(id).orElseThrow().deactivate();}
 @GetMapping("/methods") List<MethodDto> methods(){return Arrays.stream(StrategyDefinition.values()).map(x->new MethodDto(x.name(),x.label())).toList();}
 @PostMapping("/run-now") PredictionService.RunResult run(@Valid @RequestBody RunRequest r){return service.snapshot(r.name());}
 @GetMapping("/runs") List<RunSummary> runs(){return runs.findTop100ByOrderByCreatedAtDesc().stream().map(this::summary).toList();}
 @GetMapping("/runs/{id}") RunReport run(@PathVariable long id){var r=runs.findById(id).orElseThrow();var ps=predictions.findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(id);return new RunReport(summary(r),ps);}
 @GetMapping("/method-report") MethodReport method(@RequestParam String method,@RequestParam(defaultValue="3600")long horizon){var ps=predictions.findByMethodNameAndSignalVersionOrderByPredictedAtDesc(method,2).stream().filter(p->p.getHorizonSeconds()==horizon).toList();long graded=ps.stream().filter(p->p.getOutcome()!=Outcome.PENDING).count(),correct=ps.stream().filter(p->p.getOutcome()==Outcome.CORRECT).count();return new MethodReport(method,ps.size(),graded,correct,graded==0?0:correct*100.0/graded,ps);}
 @GetMapping("/prices") Map<String,LivePriceStream.PriceTick> currentPrices(){return prices.latest();}
 @GetMapping("/chart/{pair}") List<BinanceMarketDataClient.Candle> chart(@PathVariable String pair,@RequestParam(defaultValue="15m")String interval,@RequestParam(defaultValue="120")int limit){return market.chart(pair.toUpperCase(Locale.ROOT),interval,limit);}
 @GetMapping("/reports/coins") List<ReportService.CoinReport> coinReports(@RequestParam(defaultValue="3")@Min(1)int minSamples,@RequestParam(defaultValue="3600")long horizon){return reports.coinReports(minSamples,horizon);}
 @GetMapping("/reports/mixes") List<ReportService.MixAccuracy> mixReports(@RequestParam(defaultValue="3")@Min(1)int minSamples,@RequestParam(defaultValue="3")int size,@RequestParam(defaultValue="3600")long horizon){return reports.mixReports(minSamples,size,horizon);}
 @GetMapping("/reports/coin-mixes") List<ReportService.CoinMixReport> coinMixReports(@RequestParam(defaultValue="3")@Min(1)int minSamples,@RequestParam(defaultValue="3")int size,@RequestParam(defaultValue="3600")long horizon){return reports.coinMixReports(minSamples,size,horizon);}
 @GetMapping("/reports/super") ReportService.SuperReport superReport(@RequestParam(defaultValue="5")@Min(1)int minSamples,@RequestParam(defaultValue="3600")long horizon){return reports.superReport(minSamples,horizon);}
 @GetMapping(value="/reports/super/excel",produces="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet") ResponseEntity<byte[]> superExcel(){return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=super-analysis.xlsx").body(excel.superAnalysis());}
 @PostMapping("/reports/money") ReportService.MoneyReport moneyReport(@RequestBody ReportService.MoneyRequest request){return reports.moneyReport(request);}
 @GetMapping("/predictions") List<Prediction> history(@RequestParam(required=false)String coin,@RequestParam(required=false)String method,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant from,@RequestParam(required=false)@DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME)Instant to){return predictions.search(blank(coin),blank(method),from==null?Instant.now().minus(Duration.ofDays(30)):from,to==null?Instant.now():to);}
 @GetMapping("/leaderboard") List<LeaderRow> board(@RequestParam(required=false)String coin,@RequestParam(defaultValue="7d")String window,@RequestParam(defaultValue="3600")long horizon){if(horizon==0)throw new IllegalArgumentException("select one time horizon");Instant from=Instant.now().minus(parseWindow(window));return predictions.leaderboard(blank(coin),from,horizon).stream().map(r->{long total=(Long)r[1], correct=((Number)r[2]).longValue();return new LeaderRow((String)r[0],total,correct,total==0?0:correct*100.0/total);}).sorted(Comparator.comparingDouble(LeaderRow::accuracy).reversed()).toList();}
 @GetMapping(value="/prices/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE) Flux<LivePriceStream.PriceTick> live(){return prices.flux();}
 @GetMapping("/settings") AppSettings setting(){return settings.findById(1).orElseThrow();}
 @PutMapping("/settings") @Transactional AppSettings setting(@Valid @RequestBody SettingsRequest r){var s=settings.findById(1).orElseThrow();s.update(r.snapshotIntervalSeconds(),r.gradingHorizonSeconds());return s;}
 private String blank(String s){return s==null||s.isBlank()?null:s.toUpperCase(Locale.ROOT);}
 private Duration parseWindow(String w){long n=Long.parseLong(w.substring(0,w.length()-1));return switch(w.charAt(w.length()-1)){case'h'->Duration.ofHours(n);case'd'->Duration.ofDays(n);case'w'->Duration.ofDays(n*7);default->throw new IllegalArgumentException("window must end in h, d, or w");};}
 private RunSummary summary(AnalysisRun r){var ps=predictions.findByAnalysisRunIdOrderByCoinSymbolAscMethodNameAsc(r.getId());long pending=ps.stream().filter(p->p.getOutcome()==Outcome.PENDING).count(),correct=ps.stream().filter(p->p.getOutcome()==Outcome.CORRECT).count(),graded=ps.size()-pending;return new RunSummary(r.getId(),r.getName(),r.getCreatedAt(),r.getHorizonSeconds(),r.getStatus().name(),ps.size(),pending,correct,graded==0?0:correct*100.0/graded,r.getErrorCount());}
 public record CoinRequest(@NotBlank @Pattern(regexp="[A-Za-z0-9]{2,20}")String symbol,@NotBlank @Pattern(regexp="[A-Za-z0-9]{4,30}")String pair){}
 public record SettingsRequest(@Min(60)long snapshotIntervalSeconds,@Min(60)long gradingHorizonSeconds){}
 public record RunRequest(@NotBlank @Size(max=120)String name,Integer checkerMinutes){}
 public record RunSummary(long id,String name,Instant createdAt,long horizonSeconds,String status,long predictions,long pending,long correct,double accuracy,int errors){}
 public record RunReport(RunSummary summary,List<Prediction> predictions){}
 public record MethodReport(String method,long total,long graded,long correct,double accuracy,List<Prediction> predictions){}
 public record MethodDto(String id,String name){} public record LeaderRow(String method,long total,long correct,double accuracy){}
}
