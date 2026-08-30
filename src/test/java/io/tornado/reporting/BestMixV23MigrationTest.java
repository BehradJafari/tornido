package io.tornado.reporting;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;

import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BestMixV23MigrationTest {
    @Test void v22UpgradeKeepsOnlyEligibleTp1LiveWinnerAndEnforcesSingleSlice() throws Exception {
        String url="jdbc:h2:mem:best-mix-v23;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration","classpath:db/vendor/h2").target(MigrationVersion.fromVersion("22")).load().migrate();
        try(Connection connection=DriverManager.getConnection(url,"sa","");Statement sql=connection.createStatement()){
            sql.executeUpdate("INSERT INTO app_settings(id,snapshot_interval_seconds,grading_horizon_seconds) VALUES(1,900,900)");
            sql.executeUpdate("UPDATE app_settings SET minimum_mix_simulation_trades=50, minimum_notification_win_rate_percent=87 WHERE id=1");
            sql.executeUpdate("INSERT INTO coins(id,symbol,pair,active,created_at) VALUES(100,'BTC','BTCUSDT',TRUE,CURRENT_TIMESTAMP)");
            insert(sql,1,3600,1,".30",90);insert(sql,2,3600,2,".50",95);insert(sql,3,14400,1,".30",50);
        }

        Flyway.configure().dataSource(url,"sa","").locations("classpath:db/migration","classpath:db/vendor/h2").load().migrate();

        try(Connection connection=DriverManager.getConnection(url,"sa","");Statement sql=connection.createStatement()){
            try(ResultSet rows=sql.executeQuery("SELECT horizon_seconds,tp_level,target_hit_rate FROM best_method_mixes")){assertThat(rows.next()).isTrue();assertThat(rows.getLong(1)).isEqualTo(3600);assertThat(rows.getInt(2)).isEqualTo(1);assertThat(rows.getDouble(3)).isEqualTo(90);assertThat(rows.next()).isFalse();}
            assertThatThrownBy(()->insert(sql,4,3600,1,".30",92)).isInstanceOf(SQLException.class).hasMessageContaining("UK_BEST_MIX_SLICE_VERSION");
        }
    }

    private void insert(Statement sql,long id,long horizon,int tp,String target,double rate)throws SQLException{sql.executeUpdate("INSERT INTO best_method_mixes(id,coin_id,horizon_seconds,mix_size,rank_number,strategy_codes,strategy_versions,method_names,samples,target_hits,target_hit_rate,directional_correct,directional_accuracy,wilson_score,ranking_score,signal_version,tp_level,target_percent,calculated_at) VALUES("+id+",100,"+horizon+",2,1,'A||B','1||1','A||B',100,"+(long)rate+","+rate+",90,90,80,80,3,"+tp+","+target+",CURRENT_TIMESTAMP)");}
}
