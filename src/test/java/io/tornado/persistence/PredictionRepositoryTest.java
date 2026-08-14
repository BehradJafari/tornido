package io.tornado.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties={
        "spring.datasource.url=jdbc:h2:mem:repository-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
class PredictionRepositoryTest {
    @Autowired PredictionRepository predictions;
    @Autowired TestEntityManager entityManager;

    @Test void reportAndDueQueriesReturnJoinedProjectionWithoutAdditionalEntityLoads(){
        Coin coin=entityManager.persist(new Coin("BTC","BTCUSDT"));
        AnalysisRun run=entityManager.persist(new AnalysisRun("test",Duration.ZERO));
        Instant predictedAt=Instant.now().minusSeconds(120);
        Prediction prediction=new Prediction(run,coin,"A",predictedAt,Direction.UP,new BigDecimal("100"),Duration.ofMinutes(1));
        entityManager.persist(prediction);entityManager.flush();

        assertThat(predictions.findDue(Outcome.PENDING,2,Instant.now())).containsExactly(prediction);
        prediction.grade(new BigDecimal("101"),predictedAt.plusSeconds(60),new BigDecimal("0.003"));
        entityManager.flush();entityManager.clear();

        var rows=predictions.findGradedReportRows(60,2);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().getCoinSymbol()).isEqualTo("BTC");
        assertThat(rows.getFirst().getMethodName()).isEqualTo("A");
        assertThat(predictions.summarizeRuns(java.util.List.of(run.getId())).getFirst().getTargetCorrect()).isEqualTo(1);
    }
}
