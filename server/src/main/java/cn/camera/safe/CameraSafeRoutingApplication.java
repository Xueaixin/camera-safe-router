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
        if (args.length > 0
                && ("graph-build".equals(args[0])
                        || "graph-check".equals(args[0])
                        || "boundary-generate".equals(args[0]))) {
            cn.camera.safe.cli.RoutingDataTool.main(args);
            return;
        }
        SpringApplication.run(CameraSafeRoutingApplication.class, args);
    }
}
