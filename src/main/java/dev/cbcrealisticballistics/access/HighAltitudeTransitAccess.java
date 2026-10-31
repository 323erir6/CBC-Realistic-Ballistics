package dev.cbcrealisticballistics.access;

public interface HighAltitudeTransitAccess {
    boolean cbcRealisticBallistics$isHighAltitudeTransitActive();

    void cbcRealisticBallistics$activateHighAltitudeTransit();

    long cbcRealisticBallistics$getLastServerTick();

    void cbcRealisticBallistics$setLastServerTick(long gameTime);
}
