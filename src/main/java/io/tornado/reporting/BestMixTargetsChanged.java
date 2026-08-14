package io.tornado.reporting;

import io.tornado.persistence.TpSlLevels;

public record BestMixTargetsChanged(TpSlLevels previous, TpSlLevels current) {}
