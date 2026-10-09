package io.twinpilot.api.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final TwinPilotProperties props;

    public WebConfig(TwinPilotProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(props.cors().allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS");
    }

    /**
     * 정적에 가까운 레이어(GeoJSON)는 ETag로 재전송을 줄인다.
     * SSE 경로는 응답을 버퍼링하면 안 되므로 레이어 경로에만 등록한다.
     * Tomcat은 strong ETag 응답을 gzip 압축하지 않으므로 weak ETag를 쓴다.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> layerEtagFilter() {
        ShallowEtagHeaderFilter filter = new ShallowEtagHeaderFilter();
        filter.setWriteWeakETag(true);
        FilterRegistrationBean<ShallowEtagHeaderFilter> bean = new FilterRegistrationBean<>(filter);
        bean.addUrlPatterns("/api/v1/regions", "/api/v1/roads", "/api/v1/facilities", "/api/v1/buildings",
                "/api/v1/bus-routes");
        return bean;
    }

    /** 병렬 조회·비동기 분석에 쓰는 가상 스레드 실행기. */
    @Bean(destroyMethod = "close")
    public ExecutorService virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
