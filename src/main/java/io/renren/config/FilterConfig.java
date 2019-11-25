package io.renren.config;

import io.renren.common.xss.XssFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.DelegatingFilterProxy;

import javax.servlet.DispatcherType;

/**
 * Filter配置
 */
@Configuration
public class FilterConfig {

    /**
     * SpringBoot针对Servlet,Filter,Listener可以使用@Bean来进行自动注册
     * @return
     */
    @Bean
    public FilterRegistrationBean shiroFilterRegistration() {
        //Springboot中会使用FilterRegistrationBean来注册Filter,https://cloud.tencent.com/developer/article/1413448
        FilterRegistrationBean registration = new FilterRegistrationBean();
        // 注入过滤器
        registration.setFilter(new DelegatingFilterProxy("shiroFilter"));
        //该值缺省为false,表示生命周期有SpringApplicationContext管理，设置为true则表示由ServlectContainer管理
        registration.addInitParameter("targetFilterLifecycle","true");
        // 是否自动注册 false 取消Filter的自动注册
        registration.setEnabled(true);
        //过滤器顺序  当有多个拦截器的时候，可以再添加registratrion.setOrder(**)进行设置
        registration.setOrder(Integer.MAX_VALUE - 1);
        //过滤应用程序中所有资源,当前应用程序根下的所有文件包括多级子目录下的所有文件，注意这里*前有“/”
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    public FilterRegistrationBean xssFilterRegistration() {
        FilterRegistrationBean registration = new FilterRegistrationBean();
        registration.setDispatcherTypes(DispatcherType.REQUEST);
        registration.setFilter(new XssFilter());
        registration.addUrlPatterns("/*");
        // 过滤器名称
        registration.setName("xssFilter");
        registration.setOrder(Integer.MAX_VALUE);
        return registration;
    }
}
