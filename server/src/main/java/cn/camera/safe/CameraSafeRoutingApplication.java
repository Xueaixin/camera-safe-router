package cn.camera.safe;

import cn.camera.safe.config.AppProperties;
import cn.camera.safe.config.SixthRingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties({AppProperties.class, SixthRingProperties.class})
@EnableScheduling
public class CameraSafeRoutingApplication {
    public static void main(String[] args) {
        SpringApplication.run(CameraSafeRoutingApplication.class, args);
    }
}
