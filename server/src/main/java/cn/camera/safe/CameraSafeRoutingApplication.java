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
    /** 进程启动时刻（nanoTime），用于服务就绪日志计算启动耗时。 */
    public static final long STARTED_AT_NANOS = System.nanoTime();

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
