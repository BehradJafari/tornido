package io.tornado.persistence;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppSettingsTest {
    @Test void mixSimulationSettingsArePersistedInEntity(){
        AppSettings settings=new AppSettings(900,900);settings.updateMixSignals(45,new java.math.BigDecimal("0.75"),false);
        assertThat(settings.getMinimumMixSimulationTrades()).isEqualTo(45);assertThat(settings.getMixTradeStopLossPercent()).isEqualByComparingTo("0.75");assertThat(settings.isTelegramDailyReportEnabled()).isFalse();
    }

    @Test void validatesMixSimulationSettings(){
        AppSettings settings=new AppSettings(900,900);
        assertThatThrownBy(()->settings.updateMixSignals(0,new java.math.BigDecimal("0.5"),true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("minimum mix");
        assertThatThrownBy(()->settings.updateMixSignals(30,new java.math.BigDecimal("21"),true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("stop loss");
    }

    @Test void validatesAndStoresIndependentTpSlLadders(){AppSettings settings=new AppSettings(900,900);TpSlLevels levels=new TpSlLevels(new java.math.BigDecimal(".3"),new java.math.BigDecimal(".5"),new java.math.BigDecimal("1"),new java.math.BigDecimal(".2"),new java.math.BigDecimal(".4"),new java.math.BigDecimal(".8"));settings.updateMixSignals(40,levels,false);assertThat(settings.getTpSlLevels()).isEqualTo(levels);assertThat(settings.getMixTradeStopLossPercent()).isEqualByComparingTo(".4");assertThatThrownBy(()->settings.updateMixSignals(40,new TpSlLevels(new java.math.BigDecimal(".5"),new java.math.BigDecimal(".3"),new java.math.BigDecimal("1"),new java.math.BigDecimal(".2"),new java.math.BigDecimal(".4"),new java.math.BigDecimal(".8")),true)).hasMessageContaining("strictly increasing");}

    @Test void validatesNotificationWinRateBoundaries(){AppSettings settings=new AppSettings(900,900);settings.updateMixSignals(40,settings.getTpSlLevels(),java.math.BigDecimal.ZERO,false);assertThat(settings.getMinimumNotificationWinRatePercent()).isEqualByComparingTo("0");settings.updateMixSignals(40,settings.getTpSlLevels(),new java.math.BigDecimal("100"),false);assertThat(settings.getMinimumNotificationWinRatePercent()).isEqualByComparingTo("100");assertThatThrownBy(()->settings.updateMixSignals(40,settings.getTpSlLevels(),new java.math.BigDecimal("-1"),false)).hasMessageContaining("between 0 and 100");assertThatThrownBy(()->settings.updateMixSignals(40,settings.getTpSlLevels(),new java.math.BigDecimal("101"),false)).hasMessageContaining("between 0 and 100");}

    @Test void validatesAndStoresProfileResearchPolicy(){AppSettings settings=new AppSettings(900,900);settings.updateProfileSelection(120,300,new java.math.BigDecimal("3.5"),new java.math.BigDecimal("0.12"),48,true);assertThat(settings.getMinimumConfigurationSamples()).isEqualTo(120);assertThat(settings.getCoinProfileMinimumSamples()).isEqualTo(300);assertThat(settings.getProfileReplacementMinimumImprovementPercent()).isEqualByComparingTo("3.5");assertThat(settings.getProfileResearchRoundTripCostPercent()).isEqualByComparingTo("0.12");assertThat(settings.getProfileRefreshIntervalHours()).isEqualTo(48);assertThat(settings.isAutomaticProfileResearchEnabled()).isTrue();assertThatThrownBy(()->settings.updateProfileSelection(100,80,new java.math.BigDecimal("2"),new java.math.BigDecimal(".1"),24,false)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("coin profile minimum");}
}
