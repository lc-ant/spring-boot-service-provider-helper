package net.lecousin.ant.core.springboot.service.provider.helper;

import lombok.Getter;
import lombok.Setter;
import net.lecousin.ant.connector.database.annotations.Tenant;
import net.lecousin.ant.core.expression.impl.StringFieldReference;

public abstract class MultiTenantEntity {

	public static final StringFieldReference FIELD_TENANT_ID = new StringFieldReference("tenantId");
	
	@Getter
	@Setter
	@Tenant
	private String tenantId;
	
}
