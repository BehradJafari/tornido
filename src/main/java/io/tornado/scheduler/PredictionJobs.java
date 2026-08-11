package io.tornado.scheduler;
import org.slf4j.Logger; import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled; import org.springframework.stereotype.Component;
@Component public class PredictionJobs {
 private static final Logger log=LoggerFactory.getLogger(PredictionJobs.class);
 private final PredictionService service; public PredictionJobs(PredictionService s){service=s;}
 @Scheduled(fixedDelayString="${tornado.scheduler.snapshot-interval}",initialDelayString="${tornado.scheduler.snapshot-initial-delay:10s}") void snapshot(){log.info("Starting automatic multi-horizon analysis");var result=service.snapshot();log.info("Automatic analysis {} completed: {} predictions, {} coin errors",result.runId(),result.predictionsCreated(),result.errors().size());}
 @Scheduled(fixedDelayString="${tornado.scheduler.grading-interval}") void grade(){int count=service.gradeDue();if(count>0)log.info("Graded {} due predictions",count);}
}
