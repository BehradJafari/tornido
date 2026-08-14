package io.tornado.persistence;
import org.junit.jupiter.api.Test;import java.math.BigDecimal;import static org.assertj.core.api.Assertions.*;
class TpSlLevelsTest{
 @Test void calculatesIndependentLongAndShortPrices(){var x=new TpSlLevels(b(".3"),b(".5"),b("1"),b(".2"),b(".4"),b(".8"));assertThat(x.price(b("100"),Direction.UP,true,1)).isEqualByComparingTo("100.3");assertThat(x.price(b("100"),Direction.UP,false,3)).isEqualByComparingTo("99.2");assertThat(x.price(b("100"),Direction.DOWN,true,2)).isEqualByComparingTo("99.5");assertThat(x.price(b("100"),Direction.DOWN,false,2)).isEqualByComparingTo("100.4");}
 @Test void rejectsUnorderedDuplicateZeroNegativeAndExcessiveLevels(){assertThatThrownBy(()->new TpSlLevels(b(".5"),b(".3"),b("1"),b(".2"),b(".4"),b(".8"))).hasMessageContaining("strictly increasing");assertThatThrownBy(()->new TpSlLevels(b(".3"),b(".3"),b("1"),b(".2"),b(".4"),b(".8"))).hasMessageContaining("strictly increasing");assertThatThrownBy(()->new TpSlLevels(b("0"),b(".5"),b("1"),b(".2"),b(".4"),b(".8"))).hasMessageContaining("between");assertThatThrownBy(()->new TpSlLevels(b(".3"),b(".5"),b("21"),b(".2"),b(".4"),b(".8"))).hasMessageContaining("between");assertThatThrownBy(()->new TpSlLevels(b(".3"),b(".5"),b("1"),b("-.2"),b(".4"),b(".8"))).hasMessageContaining("between");}
 private BigDecimal b(String x){return new BigDecimal(x);}
}
