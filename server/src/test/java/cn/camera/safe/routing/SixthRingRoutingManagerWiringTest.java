package cn.camera.safe.routing;

import cn.camera.safe.config.SixthRingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SixthRingRoutingManagerWiringTest {

    @Test
    void springSelectsTheProductionConstructor() {
        new ApplicationContextRunner()
                .withBean(GraphHopperManager.class, () -> mock(GraphHopperManager.class))
                .withBean(SixthRingProperties.class, () -> new SixthRingProperties(
                        "boundary.geojson", false, 50, 1000, 100, 100,
                        2_000_000, Duration.ofSeconds(5), false, 4, 50))
                .withBean(SixthRingBoundaryLoader.class,
                        () -> mock(SixthRingBoundaryLoader.class))
                .withBean(SixthRingRoutingManager.class)
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBean(SixthRingRoutingManager.class)).isNotNull();
                });
    }
}
