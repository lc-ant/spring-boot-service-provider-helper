package net.lecousin.ant.core.springboot.service.provider.helper;

import java.io.Serializable;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.lecousin.ant.connector.database.DatabaseConnector;
import net.lecousin.ant.connector.database.EntityMeta;
import net.lecousin.ant.connector.database.annotations.Tenant.TenantStrategy;
import net.lecousin.ant.core.api.PageRequest;
import net.lecousin.ant.core.expression.Expression;
import net.lecousin.ant.core.expression.impl.ConditionAnd;
import net.lecousin.ant.core.springboot.cache.CacheExpirationService;
import net.lecousin.ant.core.springboot.connector.ConnectorService;
import net.lecousin.ant.core.springboot.utils.SpringEnvironmentUtils;
import net.lecousin.ant.service.tenant.TenantService;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
@ConditionalOnClass({TenantService.class, DatabaseConnector.class})
@RequiredArgsConstructor
public class MultiTenantServiceHelper implements ApplicationContextAware {

	private final DefaultServiceAuthenticationProvider serviceAuth;
	private final TenantService tenantClient;
	private final ConnectorService connectorService;
	private final CacheExpirationService cache;
	
	@Setter
	private ApplicationContext applicationContext;
	
	// TODO listen to tenant changes and update cache
	
	@SuppressWarnings("unchecked")
	public Mono<DatabaseConnector> getDb(String serviceName, String tenantId) {
		return cache.get(
			"MultiTenantServiceHelper.tenantDbConfig",
			serviceName + ':' + tenantId,
			() -> getTenantDatabaseConfig(serviceName, tenantId),
			Duration.ofMinutes(15),
			v -> Mono.empty()
		)
		.switchIfEmpty(Mono.error(new RuntimeException("Missing database configuration for service " + serviceName + " on tenant " + tenantId + ", and no property lc-ant.service." + serviceName + ".default-database")))
		.flatMap(config -> connectorService.getConnector(DatabaseConnector.class, (String) config.get("implementation"), tenantId, (Map<String, Object>) config.get("configuration")));
	}
	
	@SuppressWarnings("unchecked")
	private Mono<Map<String, Serializable>> getTenantDatabaseConfig(String serviceName, String tenantId) {
		return serviceAuth.executeMonoAs(serviceName, () ->
			tenantClient.getServiceConfiguration(serviceName, tenantId, "database")
			.flatMap(config -> Mono.justOrEmpty(config))
			.map(config -> (Map<String, Serializable>) config)
			.switchIfEmpty(Mono.fromCallable(() -> SpringEnvironmentUtils.getPropertyAsMap(applicationContext.getEnvironment(), "lc-ant.service." + serviceName + ".default-database")))
		);
	}
	
	public <T> Mono<T> findOneAnyTenant(String serviceName, Class<T> entityType, Expression<Boolean> condition) {
		EntityMeta meta = DatabaseConnector.getMetadata(entityType, applicationContext);
		if (meta.getTenantStrategy().isEmpty() || meta.getTenantStrategy().get().equals(TenantStrategy.MULTI_TENANT))
			return connectorService.getConnector(DatabaseConnector.class)
			.flatMap(db -> db.findOne(entityType, condition));
		return tenantClient.search(null, PageRequest.noPaging()) // TODO user pagination
		.flatMapMany(page -> Flux.fromIterable(page.getData()))
		.publishOn(Schedulers.parallel())
		.flatMap(tenant -> getDb(serviceName, tenant.getId()).zipWith(Mono.just(tenant.getId())))
		.flatMap(tuple -> tuple.getT1().findOne(entityType, new ConditionAnd(condition, MultiTenantEntity.FIELD_TENANT_ID.is(tuple.getT2()))))
		.next();
	}
	
}
