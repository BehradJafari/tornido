package io.tornado.persistence;

import jakarta.persistence.*;
@Entity @Table(name="app_settings")
public class AppSettings {
    @Id private Integer id=1;
    @Column(nullable=false) private long snapshotIntervalSeconds;
    @Column(nullable=false) private long gradingHorizonSeconds;
    protected AppSettings() {}
    public AppSettings(long snapshot,long horizon){snapshotIntervalSeconds=snapshot;gradingHorizonSeconds=horizon;}
    public long getSnapshotIntervalSeconds(){return snapshotIntervalSeconds;} public long getGradingHorizonSeconds(){return gradingHorizonSeconds;}
    public void update(long snapshot,long horizon){snapshotIntervalSeconds=snapshot;gradingHorizonSeconds=horizon;}
}
