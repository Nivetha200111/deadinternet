package dev.deadinternet.config;

import com.falkordb.Driver;
import com.falkordb.FalkorDB;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FalkorConfig {

    @Bean(destroyMethod = "close")
    public Driver falkorDriver(LensProperties properties) {
        var falkor = properties.falkor();
        if (falkor.password() == null || falkor.password().isBlank()) return FalkorDB.driver(falkor.host(), falkor.port());
        String user = falkor.username() == null || falkor.username().isBlank() ? "default" : falkor.username();
        return FalkorDB.driver(falkor.host(), falkor.port(), user, falkor.password());
    }
}
