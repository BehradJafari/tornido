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
    public byte[] superAnalysis(int signalVersion){return superAnalysis(signalVersion,1);}public byte[] superAnalysis(int signalVersion,int tpLevel){
        var coinList=coins.findAllByActiveTrueOrderBySymbol();var rows=reports.superExcelRows(signalVersion,tpLevel);
        Map<String,ReportService.ExcelSliceRow> byKey=new HashMap<>();rows.forEach(r->byKey.put(r.coin()+":"+r.horizon(),r));
        try(var workbook=new XSSFWorkbook();var output=new ByteArrayOutputStream()){
            CellStyle header=header(workbook),percent=workbook.createCellStyle();percent.setDataFormat(workbook.createDataFormat().getFormat("0.00%"));Sheet metadata=workbook.createSheet("Report");writeRow(metadata.createRow(0),header,"Selected TP level","Target definition","Generated at");writeRow(metadata.createRow(1),null,"TP"+tpLevel,"Configured TP"+tpLevel+" percentage",java.time.Instant.now());autosize(metadata,3);
            Sheet coinSheet=workbook.createSheet("Coins");writeRow(coinSheet.createRow(0),header,"Symbol","Binance pair","Active");int coinRow=1;for(var coin:coinList)writeRow(coinSheet.createRow(coinRow++),null,coin.getSymbol(),coin.getPair(),"Yes");coinSheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,coinRow-1),0,2));autosize(coinSheet,3);
            for(int h=0;h<HORIZONS.length;h++){
                Sheet sheet=workbook.createSheet(SHEETS[h]);List<String> headings=new ArrayList<>(List.of("Coin","Time slice"));for(int size=1;size<=6;size++)headings.addAll(List.of("Best "+size+" methods","Target-hit rate","Directional accuracy","All predictions","Decisive predictions","Target hits","Directional correct","Same direction","Same-direction target hits"));writeRow(sheet.createRow(0),header,headings.toArray());
                int rowIndex=1;for(var coin:coinList){Row row=sheet.createRow(rowIndex++);row.createCell(0).setCellValue(coin.getSymbol());row.createCell(1).setCellValue(SHEETS[h]);var data=byKey.get(coin.getSymbol()+":"+HORIZONS[h]);Map<Integer,ReportService.MixAccuracy> mixes=new HashMap<>();if(data!=null)data.bestMixes().forEach(x->{if(x.mix()!=null)mixes.put(x.size(),x.mix());});int column=2;for(int size=1;size<=6;size++){var mix=mixes.get(size);if(mix==null){column+=9;continue;}row.createCell(column++).setCellValue(String.join(" + ",mix.methods()));Cell targetRate=row.createCell(column++);targetRate.setCellValue(mix.targetHitRate()/100);targetRate.setCellStyle(percent);Cell directionRate=row.createCell(column++);directionRate.setCellValue(mix.directionalAccuracy()/100);directionRate.setCellStyle(percent);row.createCell(column++).setCellValue(mix.totalPredictions());row.createCell(column++).setCellValue(mix.samples());row.createCell(column++).setCellValue(mix.targetCorrect());row.createCell(column++).setCellValue(mix.directionalCorrect());row.createCell(column++).setCellValue(mix.sameDirectionPredictions());row.createCell(column++).setCellValue(mix.sameDirectionCorrect());}}
                sheet.createFreezePane(2,1);sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0,Math.max(0,rowIndex-1),0,55));autosize(sheet,56);
            }
            workbook.write(output);return output.toByteArray();
        }catch(Exception e){throw new IllegalStateException("Could not create Super Analysis Excel report",e);}
    }
    private CellStyle header(Workbook workbook){CellStyle style=workbook.createCellStyle();Font font=workbook.createFont();font.setBold(true);font.setColor(IndexedColors.WHITE.getIndex());style.setFont(font);style.setFillForegroundColor(IndexedColors.DARK_GREEN.getIndex());style.setFillPattern(FillPatternType.SOLID_FOREGROUND);return style;}
    private void writeRow(Row row,CellStyle style,Object... values){for(int i=0;i<values.length;i++){Cell cell=row.createCell(i);cell.setCellValue(String.valueOf(values[i]));if(style!=null)cell.setCellStyle(style);}}
    private void autosize(Sheet sheet,int columns){for(int i=0;i<columns;i++){sheet.autoSizeColumn(i);sheet.setColumnWidth(i,Math.min(sheet.getColumnWidth(i)+512,16000));}}
}
