package io.tornado;

import io.tornado.reporting.OpportunityAnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.MOCK,properties={"spring.datasource.url=jdbc:h2:mem:context-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","spring.datasource.username=sa","spring.datasource.password=","spring.task.scheduling.enabled=false","tornado.scheduler.snapshot-initial-delay=24h","tornado.scheduler.snapshot-interval=24h","tornado.scheduler.grading-interval=24h","tornado.auth.username=test-admin","tornado.auth.password=test-admin-password","tornado.auth.jwt-secret=12345678901234567890123456789012"})
class ApplicationContextTest {
    @Autowired OpportunityAnalyticsService analytics;

    @Test void contextLoadsWithCompleteMigratedSchema() {}

    @Test void opportunityQueriesRunAgainstTheMigratedSchema() {
        var filter=new OpportunityAnalyticsService.Filter(null,null,null,null,null,null,2,1,null,null,null,null,null,null,null,null,null,3);
        assertThat(analytics.dashboard(filter).summary().totalFoundSignals()).isZero();
        assertThat(analytics.list(filter,0,25).content()).isEmpty();
        assertThat(analytics.csv(filter)).isNotEmpty();
    }
}
