package net.lecousin.ant.core.springboot.service.provider.helper;

import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import net.lecousin.ant.core.springboot.service.provider.LcAntServiceProviderConfiguration;

@Configuration
@Import({LcAntServiceProviderConfiguration.class})
@ComponentScan
public class LcAntServiceProviderHelperConfiguration {

}
