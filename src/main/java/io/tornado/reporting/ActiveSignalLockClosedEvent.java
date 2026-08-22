package io.tornado.reporting;

import io.tornado.persistence.ActiveSignalLock;

public record ActiveSignalLockClosedEvent(long lockId, long simulationId, ActiveSignalLock.Status status) {}
