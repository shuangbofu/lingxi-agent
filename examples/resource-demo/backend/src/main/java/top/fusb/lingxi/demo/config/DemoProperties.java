package top.fusb.lingxi.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private String accessToken;
    private String publicBaseUrl;
}
