package com.mds.comm.base;

import com.google.common.base.Joiner;
import com.mds.crypto.v1.handler.CryptoHandler;
import com.mds.comm.enumerator.EncryptionTypeParameterEnum;
import com.mds.comm.enumerator.EncryptionTypeRequestEnum;
import com.mds.comm.exception.PatternRequestException;
import com.mds.comm.interfaces.FeignClientApi;
import com.mds.comm.interfaces.FeignConfigApi;
import com.mds.comm.model.FeignProperties;
import com.mds.comm.util.HttpClientUtil;
import com.mds.comm.wrapper.FeignRequestWrapper;
import com.mds.error.handler.exception.GeneralException;
import com.mds.token.sso.SsoSessionProvider;
import feign.InvocationContext;
import feign.RequestTemplate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.Synchronized;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Abstract base for Feign interceptors that applies SSO authentication,
 * parameter encryption, and custom header injection.
 *
 * <p>Subclasses supply their own {@link FeignConfigApi} to declare which
 * sessions are encrypted and which are not. The interceptor pipeline is:
 * <ol>
 *   <li>Resolve session and prefix via {@link FeignRequestWrapper}</li>
 *   <li>Inject {@code Authorization} / {@code X-EncryptedObject} headers</li>
 *   <li>Encrypt path and query parameters when required</li>
 *   <li>Strip the interceptor prefix from the final URL</li>
 * </ol>
 *
 * <pre>
 * public class ExampleInterceptor extends AbstractFeignClientBase {
 *
 *   protected ExampleInterceptor(CryptoHandler cryptoHandler,
 *       {@literal @}Qualifier("my-feign-config") FeignConfigApi feignConfigApi,
 *       SsoSessionProvider ssoSessionProvider) {
 *     super(cryptoHandler, feignConfigApi, ssoSessionProvider);
 *   }
 * }
 * </pre>
 *
 * @author MDS
 * @since 0.0.1-SNAPSHOT
 */
@Slf4j
public abstract class AbstractFeignClientBase extends FeignRequestWrapper implements FeignClientApi {

  protected final CryptoHandler cryptoHandler;
  protected final FeignConfigApi feignConfigApi;
  protected final SsoSessionProvider ssoSessionProvider;
  private Map<String, Collection<String>> headers;

  // *************************** Builder Method ***************************

  /**
   * Injects the crypto handler, the Feign configuration and the SSO
   * session provider.
   *
   * @param cryptoHandler      the encryption handler
   * @param feignConfigApi     the session configuration supplier
   * @param ssoSessionProvider the injected SSO session provider
   */
  protected AbstractFeignClientBase(CryptoHandler cryptoHandler,
                                    @Qualifier(value = "defaultFeignConfig") FeignConfigApi feignConfigApi,
                                    SsoSessionProvider ssoSessionProvider) {
    this.cryptoHandler = cryptoHandler;
    this.feignConfigApi = feignConfigApi;
    this.ssoSessionProvider = ssoSessionProvider;
  }

  // *************************** Public Method ***************************

  /**
   * Feign request interceptor entry point.
   *
   * @param template the outgoing request template
   */
  @Override
  @Synchronized
  public void apply(RequestTemplate template) {
    doRequestFilter(template);
  }

  /**
   * Feign response interceptor — delegates to the chain and then invokes
   * {@link #interceptCustomizer}.
   *
   * @param invocationContext the invocation context
   * @param chain             the interceptor chain
   * @return the invocation result
   * @throws Exception if the chain or customizer fails
   */
  @Override
  @Synchronized
  public Object intercept(InvocationContext invocationContext, Chain chain) throws Exception {
    Object result = chain.next(invocationContext);
    ResponseEntity<?> responseEntity;

    if (result instanceof ResponseEntity<?>) {
      responseEntity = (ResponseEntity<?>) result;
    } else {
      responseEntity = ResponseEntity.ok(result);
    }

    interceptCustomizer(invocationContext.response().request(), responseEntity);
    return result;
  }

  /** {@inheritDoc} */
  @Override
  public FeignProperties generateFeignProperties() {
    return feignConfigApi.generateFeignConfig();
  }

  /**
   * Hook invoked after each Feign response for subclass-specific
   * post-processing.
   *
   * @param request  the original Feign request
   * @param response the response entity
   */
  public abstract void interceptCustomizer(feign.Request request, ResponseEntity<?> response);

  /**
   * Orchestrates configuration, encryption, and header validation for the
   * given request template.
   *
   * @param template the outgoing request template
   */
  protected void doRequestFilter(RequestTemplate template) {
    validateFeignClientConfig(template);
    validateRequest(template);
    validateHeaders(template);
  }

  /**
   * Extension point for subclasses to add custom headers.
   *
   * @param queryParams the current query parameters
   * @param template    the outgoing request template
   */
  protected void buildContentHeaders(Map<String, Collection<String>> queryParams, RequestTemplate template) {
    // Does not perform validation on this class
  }

  // *************************** Private Method ***************************

  /**
   * Lazy-loads the Feign properties and resolves the session.
   *
   * @param template the outgoing request template
   */
  void validateFeignClientConfig(RequestTemplate template) {
    if (!existsProperties()) {
      addFeignProperties(generateFeignProperties());
    }
    performValidationForRequest(template);
  }

  /**
   * Applies encryption and URL manipulation when the session requires it.
   *
   * @param template the outgoing request template
   */
  void validateRequest(RequestTemplate template) {
    try {
      headers = new HashMap<>(template.headers());
      if (existsPrefixInUrl()) {
        if (isEncryptedSession()) {
          joinDefaultHeadersForEncryptedRequest(headers);
          validatePathParam();
          validateQueryParam();
        }
        updateUrlWithNewContext(template);
      }
    } catch (Exception ex) {
      throw new PatternRequestException(ex);
    }
  }

  /**
   * Injects SSO and encrypted-object headers for encrypted sessions.
   *
   * @param headers the mutable header map
   * @throws GeneralException if SSO retrieval fails
   */
  void joinDefaultHeadersForEncryptedRequest(Map<String, Collection<String>> headers) throws GeneralException {
    final EncryptionTypeRequestEnum encryptType = getCurrentSession().getEncryptionTypeRequest();
    validateTokenCreation(encryptType, headers);
    validateEncryptedObjectCreation(encryptType, headers);
  }

  /**
   * Adds the {@code Authorization: Bearer} header when the session strategy
   * requires it.
   *
   * @param encryptType the request encryption strategy
   * @param headers     the mutable header map
   * @throws GeneralException if token retrieval fails
   */
  void validateTokenCreation(EncryptionTypeRequestEnum encryptType, Map<String, Collection<String>> headers) throws GeneralException {
    if (EncryptionTypeRequestEnum.validateAuthorization(encryptType)) {
      final var authorization = ssoSessionProvider.getAuthorization();
      final Collection<String> authorizationCollection = HttpClientUtil.convertToCollection("Bearer ".concat(authorization));
      log.info("[AbstractFeignClientBase] - (validateTokenCreation): Authorization = {}", authorizationCollection);
      headers.put("Authorization", authorizationCollection);
    }
  }

  /**
   * Adds the {@code X-EncryptedObject} header when the session strategy
   * requires it.
   *
   * @param encryptType the request encryption strategy
   * @param headers     the mutable header map
   * @throws GeneralException if encrypted-object retrieval fails
   */
  void validateEncryptedObjectCreation(EncryptionTypeRequestEnum encryptType, Map<String, Collection<String>> headers) throws GeneralException {
    if (EncryptionTypeRequestEnum.validateEncryptedObject(encryptType)) {
      final String encryptedObject = ssoSessionProvider.getEncryptedObject();
      log.info("[AbstractFeignClientBase] - (validateEncryptedObjectCreation): EncryptedObject = {} ", encryptedObject);
      headers.put("X-EncryptedObject", HttpClientUtil.convertToCollection(encryptedObject));
    }
  }

  /**
   * Encrypts path parameters when the session's parameter strategy demands it.
   *
   * @throws GeneralException if encryption fails
   */
  void validatePathParam() throws GeneralException {
    if (EncryptionTypeParameterEnum.validatePathParam(getCurrentSession().getEncryptionTypeParameter())) {
      log.info("[AbstractFeignClientBase] - (validatePathParam): Encrypting path param...");

      AtomicReference<String> currentPath = new AtomicReference<>(getCurrentPathContext());

      HttpClientUtil.executableGeneralValidate(getPathParams(), pathFilter -> {
        final String encodedPathParam = cryptoHandler.encrypt(ssoSessionProvider.getEncryptedObject(), pathFilter.getKey(), Boolean.TRUE);
        log.info("[AbstractFeignClientBase] - (validatePathParam): Before = {} and After = {}", pathFilter.getKey(), encodedPathParam);
        addPathParam(pathFilter.getKey(), encodedPathParam);
        currentPath.set(currentPath.get().replace(pathFilter.getKey(), encodedPathParam));
      });

      replacePathParamInCurrentUrlByEncrypted(currentPath.get());
    }
  }

  /**
   * Encrypts query parameters when the session's parameter strategy demands it.
   *
   * @throws GeneralException if encryption fails
   */
  void validateQueryParam() throws GeneralException {
    if (EncryptionTypeParameterEnum.validateQueryParam(getCurrentSession().getEncryptionTypeParameter())) {
      log.info("[AbstractFeignClientBase] - (validateQueryParam): Encrypting query param...");

      Map<String, String> encryptedQuery = new HashMap<>(getQueryParams().size());

      HttpClientUtil.executableGeneralValidate(getQueryParams(), queryFilter -> {
        String valuesFilter = queryFilter.getValue().stream().findFirst().orElseThrow();
        if (!getCurrentSession().existsParamInExcludes(queryFilter.getKey())) {
          valuesFilter = cryptoHandler.encrypt(ssoSessionProvider.getEncryptedObject(), valuesFilter, Boolean.TRUE);
        }
        encryptedQuery.put(queryFilter.getKey(), valuesFilter);
      });

      final var encryptedQueryParam = Joiner.on("&").withKeyValueSeparator("=").join(encryptedQuery);
      log.info("[AbstractFeignClientBase] - (validateQueryParam): After = {} ", encryptedQueryParam);

      replaceQueryParamInCurrentPathByEncrypted(encryptedQueryParam);
    }
  }

  /**
   * Finalises headers — adds {@code Content-Type} and applies all collected
   * headers to the template.
   *
   * @param template the outgoing request template
   */
  void validateHeaders(RequestTemplate template) {
    buildContentHeaders(headers, template);
    headers.put(HttpHeaders.CONTENT_TYPE, HttpClientUtil.convertToList(MediaType.APPLICATION_JSON_VALUE));
    template.headers(headers);
  }
}
