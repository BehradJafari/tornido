package io.tornado.api;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HorizonPolicyTest {
    @Test void acceptsEverySupportedHorizon(){for(long horizon:new long[]{60,900,1800,3600,14400,43200,86400})assertThat(ReportService.requireSupportedHorizon(horizon)).isEqualTo(horizon);}
    @Test void rejectsArbitraryAndCombinedHorizons(){assertThatThrownBy(()->ReportService.requireSupportedHorizon(12345)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("horizon must be one of");assertThatThrownBy(()->ReportService.requireSupportedHorizon(0)).isInstanceOf(IllegalArgumentException.class);}
}
