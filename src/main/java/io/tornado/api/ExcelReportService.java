package io.tornado.api;

import io.tornado.persistence.CoinRepository;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import java.io.ByteArrayOutputStream;
import java.util.*;

@Service
public class ExcelReportService {
    private static final long[] HORIZONS={60,900,1800,3600,14400,43200,86400};
    private static final String[] SHEETS={"1 min","15 min","30 min","1 hour","4 hours","12 hours","24 hours"};
    private final CoinRepository coins;private final ReportService reports;
    public ExcelReportService(CoinRepository coins,ReportService reports){this.coins=coins;this.reports=reports;}

    public byte[] superAnalysis(){return superAnalysis(io.tornado.persistence.Prediction.CURRENT_SIGNAL_VERSION);}
    public byte[] superAnalysis(int signalVersion){return superAnalysis(signalVersion,1);}public byte[] superAnalysis(int signalVersion,int tpLevel){return superAnalysis(signalVersion,tpLevel,List.of(),List.of(),List.of());}
    public byte[] superAnalysis(int signalVersion,int tpLevel,Collection<String> requestedCoins,Collection<Long> requestedHorizons,Collection<Integer> requestedMixSizes){
        var activeCoins=coins.findAllByActiveTrueOrderBySymbol();Set<String> coinFilter=normalizeCoins(requestedCoins);var coinList=coinFilter.isEmpty()?activeCoins:activeCoins.stream().filter(c->coinFilter.contains(c.getSymbol().toUpperCase(Locale.ROOT))).toList();
        List<Long> horizons=normalizeHorizons(requestedHorizons);List<Integer> mixSizes=normalizeMixSizes(requestedMixSizes);
        var targetPercent=reports.targetPercent(tpLevel);var rows=reports.superExcelRows(signalVersion,tpLevel,coinList.stream().map(x->x.getSymbol()).toList(),horizons,mixSizes);
        Map<String,ReportService.ExcelSliceRow> byKey=new HashMap<>();rows.forEach(r->byKey.put(r.coin()+":"+r.horizon(),r));
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            CellStyle header=header(workbook),percent=workbook.createCellStyle();percent.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));Sheet metadata=workbook.createSheet("Report");writeRow(metadata.createRow(0),header,"Tornido Analysis Report","Selected TP","Target Percent","Signal Version","Generated");writeRow(metadata.createRow(1),null,"Endpoint target-hit semantics","TP"+tpLevel,targetPercent.stripTrailingZeros().toPlainString()+"%",signalVersion,java.time.Instant.now());setWidths(metadata,32,14,18,16,28);
            Sheet coinSheet=workbook.createSheet("Coins");writeRow(coinSheet.createRow(0),header,"Symbol","Binance pair","Active");int coinRow=1;for(var coin:coinList)writeRow(coinSheet.createRow(coinRow++),null,coin.getSymbol(),coin.getPair(),"Yes");coinSheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,coinRow-1),0,2));setWidths(coinSheet,14,20,10);
            for(long horizon:horizons){String sheetName=sheetName(horizon);
                Sheet sheet=workbook.createSheet(sheetName);List<String> headings=new ArrayList<>(List.of("Coin","Time slice"));for(int size:mixSizes)headings.addAll(List.of("Best "+size+" methods","TP"+tpLevel+" hit rate","Directional accuracy","All predictions","Decisive predictions","TP"+tpLevel+" hits","Directional correct","Same direction","Same-direction TP"+tpLevel+" hits"));writeRow(sheet.createRow(0),header,headings.toArray());
                int rowIndex=1;for(var coin:coinList){Row row=sheet.createRow(rowIndex++);row.createCell(0).setCellValue(coin.getSymbol());row.createCell(1).setCellValue(sheetName);var data=byKey.get(coin.getSymbol()+":"+horizon);Map<Integer,ReportService.MixAccuracy> mixes=new HashMap<>();if(data!=null)data.bestMixes().forEach(x->{if(x.mix()!=null)mixes.put(x.size(),x.mix());});int column=2;for(int size:mixSizes){var mix=mixes.get(size);if(mix==null){column+=9;continue;}row.createCell(column++).setCellValue(String.join(" + ",mix.methods()));Cell targetRate=row.createCell(column++);targetRate.setCellValue(mix.targetHitRate()/100);targetRate.setCellStyle(percent);Cell directionRate=row.createCell(column++);directionRate.setCellValue(mix.directionalAccuracy()/100);directionRate.setCellStyle(percent);row.createCell(column++).setCellValue(mix.totalPredictions());row.createCell(column++).setCellValue(mix.samples());row.createCell(column++).setCellValue(mix.targetCorrect());row.createCell(column++).setCellValue(mix.directionalCorrect());row.createCell(column++).setCellValue(mix.sameDirectionPredictions());row.createCell(column++).setCellValue(mix.sameDirectionCorrect());}}
                int columns=2+mixSizes.size()*9;sheet.createFreezePane(2,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,rowIndex-1),0,columns-1));setAnalysisWidths(sheet,mixSizes.size());
            }
            workbook.write(output);return output.toByteArray();
        }catch(Exception e){throw new IllegalStateException("Could not create Super Analysis Excel report",e);}
    }
    private CellStyle header(Workbook workbook){CellStyle style=workbook.createCellStyle();Font font=workbook.createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());style.setFont(font);style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);return style;}
    private void writeRow(Row row,CellStyle style,Object... values){for(int i=0;i<values.length;i++){Cell cell=row.createCell(i);cell.setCellValue(String.valueOf(values[i]));if(style!=null)cell.setCellStyle(style);}}
    private Set<String> normalizeCoins(Collection<String> requested){if(requested==null)return Set.of();Set<String> result=new TreeSet<>();for(String coin:requested)if(coin!=null&&!coin.isBlank())result.add(coin.trim().toUpperCase(Locale.ROOT));return result;}
    private List<Long> normalizeHorizons(Collection<Long> requested){List<Long> result=requested==null||requested.isEmpty()?Arrays.stream(HORIZONS).boxed().toList():requested.stream().distinct().sorted().toList();result.forEach(ReportService::requireSupportedHorizon);return result;}
    private List<Integer> normalizeMixSizes(Collection<Integer> requested){List<Integer> result=requested==null||requested.isEmpty()?List.of(1,2,3,4,5,6):requested.stream().distinct().sorted().toList();if(result.isEmpty()||result.stream().anyMatch(x->x==null||x<1||x>6))throw new IllegalArgumentException("mixSizes must contain values from 1 to 6");return result;}
    private String sheetName(long horizon){for(int i=0;i<HORIZONS.length;i++)if(HORIZONS[i]==horizon)return SHEETS[i];throw new IllegalArgumentException("Unsupported horizon: "+horizon);}
    private void setAnalysisWidths(Sheet sheet,int mixCount){setWidth(sheet,0,14);setWidth(sheet,1,14);for(int group=0;group<mixCount;group++){int start=2+group*9;setWidth(sheet,start,52);for(int i=1;i<9;i++)setWidth(sheet,start+i,i<=2?20:18);}}
    private void setWidths(Sheet sheet,int... widths){for(int i=0;i<widths.length;i++)setWidth(sheet,i,widths[i]);}
    private void setWidth(Sheet sheet,int column,int characters){sheet.setColumnWidth(column,Math.min(characters*256,255*256));}
}
