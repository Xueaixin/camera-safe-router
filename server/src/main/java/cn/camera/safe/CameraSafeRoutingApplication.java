package cn.camera.safe;

import cn.camera.safe.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class CameraSafeRoutingApplication {
    public static void main(String[] args) {
        SpringApplication.run(CameraSafeRoutingApplication.class, args);
    }
}
