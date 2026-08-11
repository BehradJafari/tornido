package io.tornado.persistence;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface AnalysisRunRepository extends JpaRepository<AnalysisRun,Long>{List<AnalysisRun> findTop100ByOrderByCreatedAtDesc(); Optional<AnalysisRun> findTopByOrderByCreatedAtDesc();}
