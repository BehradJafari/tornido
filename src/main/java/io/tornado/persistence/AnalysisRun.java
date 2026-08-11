package io.tornado.persistence;

import jakarta.persistence.*;
import java.time.*;

@Entity @Table(name="analysis_runs")
public class AnalysisRun {
    public enum Status { RUNNING, COMPLETED, PARTIAL, FAILED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,length=120) private String name;
    @Column(nullable=false) private Instant createdAt=Instant.now();
    @Column(nullable=false) private long horizonSeconds;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) private Status status=Status.RUNNING;
    private Instant completedAt;
    @Column(nullable=false) private int errorCount;
    protected AnalysisRun(){}
    public AnalysisRun(String name,Duration horizon){this.name=name;horizonSeconds=horizon.toSeconds();}
    public void complete(int errors,int created){errorCount=errors;completedAt=Instant.now();status=created==0?Status.FAILED:errors>0?Status.PARTIAL:Status.COMPLETED;}
    public Long getId(){return id;} public String getName(){return name;} public Instant getCreatedAt(){return createdAt;}
    public long getHorizonSeconds(){return horizonSeconds;} public Status getStatus(){return status;} public Instant getCompletedAt(){return completedAt;} public int getErrorCount(){return errorCount;}
}
