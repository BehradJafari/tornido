package io.tornado.api;

import io.tornado.persistence.Coin;
import io.tornado.persistence.CoinRepository;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExcelReportServiceTest {
    @Test
    void workbookLabelsSelectedTpAndExactConfiguredPercent() throws Exception {
        CoinRepository coins=mock(CoinRepository.class);
        ReportService reports=mock(ReportService.class);
        when(coins.findAllByActiveTrueOrderBySymbol()).thenReturn(List.of());
        when(reports.targetPercent(2)).thenReturn(new BigDecimal("0.55"));
        when(reports.superExcelRows(eq(3),eq(2),anyCollection(),anyCollection(),anyCollection())).thenReturn(List.of());

        byte[] bytes=new ExcelReportService(coins,reports).superAnalysis(3,2);

        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
            var report=book.getSheet("Report");
            assertThat(report.getRow(1).getCell(1).getStringCellValue()).isEqualTo("TP2");
            assertThat(report.getRow(1).getCell(2).getStringCellValue()).isEqualTo("0.55%");
            assertThat(book.getSheet("15 min").getRow(0).getCell(3).getStringCellValue()).isEqualTo("TP2 hit rate");
        }
    }

    @Test
    void filtersCoinsHorizonsAndMixSizesBeforeBuildingWorkbook() throws Exception {
        CoinRepository coins=mock(CoinRepository.class);
        ReportService reports=mock(ReportService.class);
        when(coins.findAllByActiveTrueOrderBySymbol()).thenReturn(List.of(new Coin("BTC","BTCUSDT"),new Coin("ETH","ETHUSDT")));
        when(reports.targetPercent(1)).thenReturn(new BigDecimal("0.31"));
        when(reports.superExcelRows(eq(3),eq(1),eq(List.of("BTC")),eq(List.of(900L)),eq(List.of(2,4)))).thenReturn(List.of());

        byte[] bytes=new ExcelReportService(coins,reports).superAnalysis(3,1,List.of("btc"),List.of(900L),List.of(4,2));

        try(var book=new XSSFWorkbook(new ByteArrayInputStream(bytes))){
            assertThat(book.getNumberOfSheets()).isEqualTo(3);
            assertThat(book.getSheet("15 min")).isNotNull();
            assertThat(book.getSheet("1 hour")).isNull();
            assertThat(book.getSheet("Coins").getLastRowNum()).isEqualTo(1);
            assertThat(book.getSheet("15 min").getRow(0).getLastCellNum()).isEqualTo((short)20);
            assertThat(book.getSheet("15 min").getColumnWidth(2)).isEqualTo(52*256);
        }
    }
}
