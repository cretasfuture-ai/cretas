package p10.clock;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Imported by the private P10 test configuration; no product source changes are needed. */
@Configuration(proxyBeanMethods = false)
public class P10ClockSpringConfiguration {
    @Bean
    public FilterRegistrationBean<P10BusinessDateFilter> p10BusinessDateFilter() {
        FilterRegistrationBean<P10BusinessDateFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new P10BusinessDateFilter());
        registration.addUrlPatterns("P31".equals(System.getProperty("p10.profile"))
                ? "/api/mobile/FOOD_3101_001/*" : "/api/mobile/VISION-ENTRY-A/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.setName("p10BusinessDateFilter");
        return registration;
    }
}
