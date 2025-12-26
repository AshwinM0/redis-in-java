package redis.mocks;

import redis.time.Clock;

public class FakeClock implements Clock {
    private long now;

    public FakeClock(long startMillis) {
        this.now = startMillis;
    }

    @Override
    public long nowMillis() {
        return now;
    }

    public void advanceMillis(long millis) {
        this.now += millis;
    }

    public void advanceSeconds(long seconds) {
        this.now += seconds * 1000;
    }
}
