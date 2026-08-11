package io.tornado.strategies;
import io.tornado.persistence.Direction;
public record StrategySignal(String method, Direction direction) {}
