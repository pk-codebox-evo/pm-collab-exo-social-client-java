/*
 * Copyright (C) 2003-2011 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.exoplatform.social.client.core.net;

import java.io.IOException;
import java.security.KeyStore;

import org.apache.http.HttpException;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpRequestInterceptor;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.AuthState;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.params.HttpClientParams;
import org.apache.http.client.protocol.ClientContext;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpConnectionParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.ExecutionContext;
import org.apache.http.protocol.HttpContext;
import org.exoplatform.social.client.api.SocialClientContext;
import org.exoplatform.social.client.api.SocialClientLibException;
import org.exoplatform.social.client.api.auth.UnAuthenticatedException;
import org.exoplatform.social.client.api.net.SocialHttpClient;

/**
 * Created by The eXo Platform SAS Author : eXoPlatform exo@exoplatform.com Jun
 * 29, 2011
 */
public final class SocialHttpClientImpl implements SocialHttpClient {
  
  private static String USER_AGENT = "eXo/ (Android)";
  
  public static void setUserAgent(String agent) {
    USER_AGENT = agent;
  }

  // Gzip of data shorter than this probably won't be worthwhile
  public static long              DEFAULT_SYNC_MIN_GZIP_BYTES = 256;

  // Default connection and socket timeout of 60 seconds. Tweak to taste.
  private static final int        SOCKET_OPERATION_TIMEOUT    = 60 * 1000;

  private final DefaultHttpClient delegate;

  /*
   * From Apache HttpClient 4, the client always check if site require
   * authenticate before sending password hash. This HttpRequestInterceptor
   * bypass that step to make request run faster.
   */
  private HttpRequestInterceptor  preemptiveAuthInterceptor   = new HttpRequestInterceptor() {
                                                                @Override
                                                                public void process(HttpRequest request, HttpContext context) throws HttpException,
                                                                                                                             IOException {
                                                                  AuthState authState = (AuthState) context.getAttribute(ClientContext.TARGET_AUTH_STATE);
                                                                  CredentialsProvider credsProvider = (CredentialsProvider) context.getAttribute(ClientContext.CREDS_PROVIDER);
                                                                  HttpHost targetHost = (HttpHost) context.getAttribute(ExecutionContext.HTTP_TARGET_HOST);

                                                                  if (authState.getAuthScheme() == null) {
                                                                    AuthScope authScope = new AuthScope(targetHost.getHostName(),
                                                                                                        targetHost.getPort());
                                                                    Credentials creds = credsProvider.getCredentials(authScope);
                                                                    if (creds != null) {
                                                                      authState.setAuthScheme(new BasicScheme());
                                                                      authState.setCredentials(creds);
                                                                    }
                                                                  }
                                                                }
                                                              };

  /**
   * Create a new HttpClient with reasonable defaults.
   * 
   * @return SocialHttpClient for you to use for all your requests.
   */
  public static SocialHttpClient newInstance() {
    HttpParams params = new BasicHttpParams();

    // Turn off stale checking. Our connections break all the time anyway,
    // and it's not worth it to pay the penalty of checking every time.
    HttpConnectionParams.setStaleCheckingEnabled(params, false);

    HttpConnectionParams.setConnectionTimeout(params, SOCKET_OPERATION_TIMEOUT);
    HttpConnectionParams.setSoTimeout(params, SOCKET_OPERATION_TIMEOUT);
    HttpConnectionParams.setSocketBufferSize(params, 8192);
    // Don't handle redirects -- return them to the caller. Our code
    // often wants to re-POST after a redirect, which we must do ourselves.
    HttpClientParams.setRedirecting(params, false);
    
    HttpProtocolParams.setUserAgent(params, USER_AGENT);
    
    SchemeRegistry schemeRegistry = new SchemeRegistry();
    schemeRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));

    try {
      // cf
      // http://stackoverflow.com/questions/2642777/trusting-all-certificates-using-httpclient-over-https
      // - SCL-60
      KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      trustStore.load(null, null);
      SSLSocketFactory sf = new OpenSSLSocketFactory(trustStore);
      sf.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);
      schemeRegistry.register(new Scheme("https", sf, 443));
    } catch (Exception e) {

    }
    ClientConnectionManager manager = new ThreadSafeClientConnManager(params, schemeRegistry);
    return new SocialHttpClientImpl(manager, params);
  }

  private SocialHttpClientImpl(ClientConnectionManager ccm, HttpParams params) {
    // delegate = new DefaultHttpClient(ccm, params);

    delegate = new DefaultHttpClient(ccm, params) {

      @Override
      protected BasicHttpProcessor createHttpProcessor() {
        // Add interceptor to prevent making requests from main thread.
        BasicHttpProcessor processor = super.createHttpProcessor();
        return processor;
      }

      @Override
      protected HttpContext createHttpContext() {
        // Same as DefaultHttpClient.createHttpContext() minus the
        // cookie store.
        HttpContext context = new BasicHttpContext();
        context.setAttribute(ClientContext.AUTHSCHEME_REGISTRY, getAuthSchemes());
        context.setAttribute(ClientContext.COOKIESPEC_REGISTRY, getCookieSpecs());
        context.setAttribute(ClientContext.CREDS_PROVIDER, getCredentialsProvider());
        return context;
      }
    };

  }

  @Override
  public HttpParams getParams() {

    return delegate.getParams();
  }

  @Override
  public ClientConnectionManager getConnectionManager() {
    return delegate.getConnectionManager();
  }

  @Override
  public HttpResponse execute(HttpUriRequest request) throws IOException, ClientProtocolException {
    return delegate.execute(request);
  }

  @Override
  public HttpResponse execute(HttpUriRequest request, HttpContext context) throws IOException, ClientProtocolException {
    return delegate.execute(request, context);
  }

  @Override
  public HttpResponse execute(HttpHost target, HttpRequest request) throws IOException, ClientProtocolException {
    return delegate.execute(target, request);
  }

  @Override
  public HttpResponse execute(HttpHost target, HttpRequest request, HttpContext context) throws IOException,
                                                                                        ClientProtocolException {
    return delegate.execute(target, request, context);
  }

  @Override
  public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler) throws IOException,
                                                                                            ClientProtocolException {
    return delegate.execute(request, responseHandler);
  }

  @Override
  public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException,
                                                                                                                 ClientProtocolException {
    return delegate.execute(request, responseHandler, context);

  }

  @Override
  public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler) throws IOException,
                                                                                                          ClientProtocolException {
    return delegate.execute(target, request, responseHandler);
  }

  @Override
  public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException,
                                                                                                                               ClientProtocolException {
    return delegate.execute(target, request, responseHandler, context);
  }

  @Override
  public void setBasicAuthenticateToRequest() throws SocialClientLibException {
    if (delegate == null)
      return;
    if (SocialClientContext.getUsername() == null || SocialClientContext.getPassword() == null) {
      // fast check from client
      throw new SocialClientLibException("401 Unauthorized", new UnAuthenticatedException());
    }
    delegate.getCredentialsProvider().setCredentials(new AuthScope(SocialClientContext.getHost(), SocialClientContext.getPort()),
                                                     new UsernamePasswordCredentials(SocialClientContext.getUsername(),
                                                                                     SocialClientContext.getPassword()));
    delegate.addRequestInterceptor(preemptiveAuthInterceptor, 0);
  }
}
