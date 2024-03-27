package net.lecousin.ant.core.springboot.service.provider.helper;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import net.lecousin.ant.core.security.LcAntSecurity;
import net.lecousin.ant.core.springboot.security.InternalJwtAuthenticationManager;
import net.lecousin.ant.core.springboot.security.JwtRequest;
import net.lecousin.ant.core.springboot.security.JwtResponse;
import net.lecousin.ant.core.springboot.security.ServiceAuthenticationProvider;
import net.lecousin.ant.service.security.SecurityService;
import net.lecousin.ant.service.security.dto.AuthenticationWithSecretRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@ConditionalOnClass({SecurityService.class})
@RequiredArgsConstructor
@Slf4j
public class DefaultServiceAuthenticationProvider implements ApplicationContextAware, ServiceAuthenticationProvider {
	
	private final SecurityService security;
	private final InternalJwtAuthenticationManager jwtAuthManager;
	
	@Setter
	private ApplicationContext applicationContext;
	
	private Map<String, Mono<JwtResponse>> tokens = new HashMap<>();
	
	@Override
	public boolean canAuthenticate(String serviceName) {
		Mono<JwtResponse> monoJwt;
		synchronized (tokens) {
			monoJwt = tokens.get(serviceName);
		}
		if (monoJwt != null) return true;
		String secret = applicationContext.getEnvironment().getProperty("lc-ant.service." + serviceName + ".secret");
		return !StringUtils.isBlank(secret);
		
	}

	public <T> Mono<T> executeMonoAs(String serviceName, Supplier<Mono<T>> command) {
		return auth(serviceName)
		.flatMap(this::getAuthentication)
		.flatMap(auth -> command.get().contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
		.checkpoint("execute as " + serviceName);
	}
	
	@Override
	public <T> Flux<T> executeFluxAs(String serviceName, Supplier<Flux<T>> command) {
		return auth(serviceName)
		.flatMap(this::getAuthentication)
		.flatMapMany(auth -> command.get().contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
		.checkpoint("execute as " + serviceName);
	}
	
	private Mono<JwtResponse> auth(String serviceName) {
		Mono<JwtResponse> monoJwt;
		CompletableFuture<JwtResponse> future = null;
		synchronized (tokens) {
			monoJwt = tokens.get(serviceName);
			if (monoJwt == null) {
				future = new CompletableFuture<>();
				monoJwt = Mono.fromFuture(future).checkpoint("authenticate service " + serviceName).cache();
				tokens.put(serviceName, monoJwt);
			}
		}
		if (future != null) {
			String secret = applicationContext.getEnvironment().getProperty("lc-ant.service." + serviceName + ".secret");
			var authRequest = AuthenticationWithSecretRequest.builder()
			.tenantId(Optional.empty())
			.subjectType(LcAntSecurity.SUBJECT_TYPE_SERVICE)
			.subjectId(serviceName)
			.secret(secret)
			.tokenDuration(Duration.ofMinutes(30))
			.renewTokenDuration(Duration.ofMinutes(40))
			.build();
			security.internalAuth().authenticateWithSecret(authRequest)
			.checkpoint("authenticate service " + serviceName)
			.subscribe(future::complete, future::completeExceptionally);
		}
		Mono<JwtResponse> request = monoJwt;
		return request.flatMap(jwt -> {
			log.debug("Auth as {}: {}", serviceName, jwt);
			if (jwt.getAccessTokenExpiresAt().isBefore(Instant.now().minus(Duration.ofMinutes(1)))) {
				log.info("Service token {} is about to expire", serviceName);
				if (jwt.getRefreshTokenExpiresAt().isAfter(Instant.now().minus(Duration.ofSeconds(30))))
					return renew(serviceName, jwt, request);
				log.info("Refresh token is also expired, re-authenticate service {}", serviceName);
				synchronized (tokens) {
					var mono = tokens.get(serviceName);
					if (mono == request) tokens.remove(serviceName);
				}
				return auth(serviceName);
			}
			return Mono.just(jwt);
		});
	}
	
	private Mono<JwtResponse> renew(String serviceName, JwtResponse jwt, Mono<JwtResponse> previousRequest) {
		log.info("Try to renew token for service {}", serviceName);
		Mono<JwtResponse> monoJwt;
		CompletableFuture<JwtResponse> future = null;
		synchronized (tokens) {
			monoJwt = tokens.get(serviceName);
			if (monoJwt != previousRequest)
				return monoJwt;
			future = new CompletableFuture<>();
			monoJwt = Mono.fromFuture(future).checkpoint("renew token for service " + serviceName).cache();
			tokens.put(serviceName, monoJwt);
		}
		var f = future;
		security.internalAuth().renewToken(new JwtRequest(jwt.getAccessToken(), jwt.getRefreshToken()))
		.checkpoint("renew token for service " + serviceName)
		.subscribe(
			future::complete,
			error -> {
				log.error("Unable to renew token for service {}", serviceName, error);
				synchronized (tokens) {
					tokens.remove(serviceName);
				}
				auth(serviceName).subscribe(f::complete, f::completeExceptionally);
			}
		);
		return monoJwt;
	}
	
	private Mono<Authentication> getAuthentication(JwtResponse jwt) {
		return jwtAuthManager.validate(jwt.getAccessToken()).checkpoint("validate service token");
	}
	
}
