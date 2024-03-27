package net.lecousin.ant.core.springboot.service.provider.helper;

import java.util.function.Function;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.parameters.P;
import org.springframework.stereotype.Service;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.lecousin.ant.connector.database.DatabaseConnector;
import net.lecousin.ant.core.api.ApiData;
import net.lecousin.ant.core.api.PageRequest;
import net.lecousin.ant.core.api.PageResponse;
import net.lecousin.ant.core.expression.Expression;
import net.lecousin.ant.core.expression.impl.ConditionAnd;
import net.lecousin.ant.core.mapping.Mappers;
import net.lecousin.ant.core.springboot.aop.Trace;
import net.lecousin.ant.core.springboot.aop.Valid;
import net.lecousin.ant.core.springboot.api.CRUDTenantService;
import net.lecousin.ant.core.validation.ValidationContext;
import reactor.core.publisher.Mono;

@Service("userServiceProviderUsers")
@Primary
@RequiredArgsConstructor
public abstract class AbstractCRUDMultiTenantService<D extends ApiData, E extends MultiTenantEntity> implements CRUDTenantService<D>, InitializingBean {

	protected final MultiTenantServiceHelper tenantHelper;
	@Getter
	protected final String serviceName;
	
	protected Function<D, E> dtoToEntity;
	protected Function<E, D> entityToDto;
	
	protected int MAX_PAGE_SIZE = 100;
	
	protected abstract Class<D> dtoClass();
	protected abstract Class<E> entityClass();
	
	@Override
	public void afterPropertiesSet() throws Exception {
		this.dtoToEntity = Mappers.createMapper(dtoClass(), entityClass());
		this.entityToDto = Mappers.createMapper(entityClass(), dtoClass());
	}
	
	protected Mono<DatabaseConnector> db(String tenantId) {
		return tenantHelper.getDb(serviceName, tenantId);
	}
	
	@Override
	@PreAuthorize("hasAuthority('per:' + #root.serviceName + ':create') && (hasAuthority('ten:' + #tenantId) || hasAuthority('root'))")
	@Trace(serviceGetterMethod = "getServiceName")
	public Mono<D> create(@P("tenantId") String tenantId, @Valid(ValidationContext.CREATION) D dto) {
		E entity = dtoToEntity.apply(dto);
		entity.setTenantId(tenantId);
		return db(tenantId)
		.flatMap(db -> db.create(entity))
		.map(entityToDto::apply);
	}
	
	@Override
	@PreAuthorize("hasAuthority('per:' + #root.serviceName + ':read') && (hasAuthority('ten:' + #tenantId) || hasAuthority('root'))")
	@Trace(serviceGetterMethod = "getServiceName")
	public Mono<PageResponse<D>> search(@P("tenantId") String tenantId, Expression<Boolean> criteria, PageRequest pageRequest) {
		Expression<Boolean> tenantCondition = MultiTenantEntity.FIELD_TENANT_ID.is(tenantId);
		pageRequest.forcePaging(MAX_PAGE_SIZE);
		return db(tenantId)
		.flatMap(db ->
			db.find(entityClass())
			.where(criteria == null ? tenantCondition : new ConditionAnd(tenantCondition, criteria))
			.paging(pageRequest)
			.execute())
		.map(page -> page.map(entityToDto));
	}
	
	@Override
	@PreAuthorize("hasAuthority('per:' + #root.serviceName + ':update') && (hasAuthority('ten:' + #tenantId) || hasAuthority('root'))")
	@Trace(serviceGetterMethod = "getServiceName")
	public Mono<D> update(@P("tenantId") String tenantId, @Valid(ValidationContext.UPDATE) D dto) {
		E entity = dtoToEntity.apply(dto);
		entity.setTenantId(tenantId);
		return db(tenantId)
		.flatMap(db -> db.update(entity))
		.map(entityToDto::apply);
	}
	
	@Override
	@PreAuthorize("hasAuthority('per:' + #root.serviceName + ':delete') && (hasAuthority('ten:' + #tenantId) || hasAuthority('root'))")
	@Trace(serviceGetterMethod = "getServiceName")
	public Mono<Void> delete(@P("tenantId") String tenantId, String id) {
		return db(tenantId)
		.flatMap(db -> db.delete(entityClass(), new ConditionAnd(MultiTenantEntity.FIELD_TENANT_ID.is(tenantId), ApiData.FIELD_ID.is(id))));
	}
	
}
