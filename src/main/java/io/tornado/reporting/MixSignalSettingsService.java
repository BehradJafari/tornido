package io.tornado.reporting;

import io.tornado.persistence.AppSettings;
import io.tornado.persistence.AppSettingsRepository;
import io.tornado.persistence.TpSlLevels;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MixSignalSettingsService {
    private final AppSettingsRepository settings;
    private final BestMixRankingPolicy rankings;
    private final ApplicationEventPublisher events;

    public MixSignalSettingsService(AppSettingsRepository settings, BestMixRankingPolicy rankings, ApplicationEventPublisher events) {
        this.settings = settings;
        this.rankings = rankings;
        this.events = events;
    }

    @Transactional
    public AppSettings update(int minimumTrades, TpSlLevels levels, boolean dailyReportEnabled) {
        AppSettings configuration = settings.findById(1).orElseThrow();
        TpSlLevels previous = configuration.getTpSlLevels();
        configuration.updateMixSignals(minimumTrades, levels, dailyReportEnabled);
        settings.saveAndFlush(configuration);
        if (!rankings.sameTargets(previous, levels)) {
            events.publishEvent(new BestMixTargetsChanged(previous, levels));
        }
        return configuration;
    }
}
