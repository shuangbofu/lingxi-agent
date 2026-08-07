package top.fusb.lingxi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class LingxiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LingxiApplication.class, args);
    }
}
