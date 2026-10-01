package com.fashion.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.nio.file.Path;

@Configuration
public class ProductAssetWebConfig implements WebMvcConfigurer {
  @Value("${atelier.assets.provider:local}") private String provider;
  @Value("${atelier.assets.directory:./data/assets}") private String directory;

  @Override public void addResourceHandlers(ResourceHandlerRegistry registry) {
    if ("local".equalsIgnoreCase(provider)) {
      String location = Path.of(directory).toAbsolutePath().normalize().toUri().toString();
      registry.addResourceHandler("/assets/**").addResourceLocations(location);
    }
  }
}
