/**
 * Copyright 2019 XEBIALABS
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package ext.deployit.onfailurehandler;

import static java.util.concurrent.TimeUnit.SECONDS;
import static com.google.common.base.Strings.isNullOrEmpty;

import java.net.URISyntaxException;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import com.xebialabs.deployit.ServerConfiguration;
import com.xebialabs.deployit.engine.spi.event.*;
import com.xebialabs.deployit.plugin.api.udm.ConfigurationItem;
import com.xebialabs.deployit.plugin.api.reflect.Type;
import com.xebialabs.xlrelease.api.XLReleaseServiceHolder;
import com.xebialabs.xlrelease.domain.Release;
import com.xebialabs.xlrelease.domain.status.ReleaseStatus;
import com.xebialabs.xlrelease.domain.variables.Variable;

import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.AuthCache;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.util.EntityUtils;
import org.apache.http.impl.auth.BasicScheme;
import org.apache.http.impl.client.BasicAuthCache;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import nl.javadude.t2bus.Subscribe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DeployitEventListener
public class OnReleaseFailureEventListener {
    private static final Logger logger = LoggerFactory.getLogger(OnReleaseFailureEventListener.class);
    
    private static final Type RELEASE_TYPE = Type.valueOf("xlrelease.Release");

    private static final String ENDPOINT_VARIABLE_NAME =
        "global.onFailureHandlerPath";
    private static final String ENDPOINT_USER = "onFailure_user";
    private static final String ENDPOINT_USER_PASSWORD_PROPERTY =
        "onFailureHandler.password";
    private static final String ENDPOINT_SCHEME = "http";
    private static final String ENDPOINT_HOST = "localhost";
    private static final int ENDPOINT_PORT = 5516;

    /*
     * The onFailure handler already contains a mechanism to try to ensure it
     * isn't executed multiple times for the same release. However, there is
     * a small window during which the endpoint cannot detect whether it has
     * been called for a certain release before.
     *
     * Since we seem to be getting two events for the same task failure in
     * rapid succession, this cache is intended to try to prevent duplicate
     * calls for the *same* failure before the endpoint has had a chance to
     * 'tag' the release.
     */
    private static final Cache<String, Boolean> FAILED_RELEASES_SEEN =
        CacheBuilder.newBuilder()
          .maximumSize(1000)
          .expireAfterWrite(10, SECONDS)
          .<String, Boolean>build();
    
    private static final ExecutorService EXECUTOR_SERVICE =
        Executors.newSingleThreadExecutor();

    private static final String UNINITIALIZED = "uninitialized";

    private static final CloseableHttpClient HTTP_CLIENT;
    private static final AuthCache PREEMPTIVE_AUTH_CACHE;
    
    static {
        HttpHost target =
            new HttpHost(ENDPOINT_HOST, ENDPOINT_PORT, ENDPOINT_SCHEME);

        String endpointUserPassword = ServerConfiguration.getInstance()
                    .getCustomPassword(ENDPOINT_USER_PASSWORD_PROPERTY);
        if (isNullOrEmpty(endpointUserPassword)) {
            logger.warn("No configuration property '{}' found in xl-release-server.conf. Calls to the onFailure handler will fail to authenticate",
                ENDPOINT_USER_PASSWORD_PROPERTY);
        }
        CredentialsProvider credentials = new BasicCredentialsProvider();
        credentials.setCredentials(
            new AuthScope(target.getHostName(), target.getPort()),
            new UsernamePasswordCredentials(ENDPOINT_USER,
                endpointUserPassword));
        HTTP_CLIENT = HttpClients.custom()
            .setDefaultCredentialsProvider(credentials)
            .build();

        PREEMPTIVE_AUTH_CACHE = new BasicAuthCache();
        PREEMPTIVE_AUTH_CACHE.put(target, new BasicScheme());
    }

    private final AtomicReference<String> handlerEndpoint = 
        new AtomicReference<String>(UNINITIALIZED);

    private String getHandlerEndpoint() {
        String value = handlerEndpoint.get();
        // don't worry about potential double calls to the API
        if (value != UNINITIALIZED) {
            return value;
        }
        
        for (Variable variable : XLReleaseServiceHolder
                .getConfigurationApi().getGlobalVariables()) {
            if (variable.getKey().equalsIgnoreCase(ENDPOINT_VARIABLE_NAME)) {
                value = variable.getValueAsString();
                handlerEndpoint.compareAndSet(UNINITIALIZED, value);
                return value;
            }
        }
        return UNINITIALIZED;
    }

    @Subscribe
    public void receiveCisUpdated(CisUpdatedEvent event) {
        for (ConfigurationItem ci : event.getCis()) {
            if (ci.getType().instanceOf(RELEASE_TYPE)) {
                final Release release = (Release) ci;
                if (release.getStatus() == ReleaseStatus.FAILED) {
                    // see comment where FAILED_RELEASES_SEEN is declared
                    if (FAILED_RELEASES_SEEN.getIfPresent(release.getId()) != null) {
                        logger.debug("Release '{}' already seen. Doing nothing", release.getId());
                    } else {
                        FAILED_RELEASES_SEEN.put(release.getId(), true);
                        /*
                         * Needs to be called from a different thread to
                         * allow this event handler to complete. Otherwise,
                         * the release cannot be modified by the handler.
                         */
                        logger.debug("Submitting runnable to invoke onFailure handler for release '{}'", release.getId());
                        EXECUTOR_SERVICE.submit(new Runnable() {
                                public void run() {
                                    try {
                                        invokeOnFailureHandler(release);
                                    } catch (IOException | URISyntaxException exception) {
                                        logger.error("Exception trying to invoke onFailure callback: {}", exception);
                                    }                            
                                }
                            });
                    }
                }
            }
        }
    }
    
    private void invokeOnFailureHandler(Release release) throws IOException, URISyntaxException {
        String handlerEndpoint = getHandlerEndpoint();
        // sentinel object so OK to use ==
        if (handlerEndpoint == UNINITIALIZED) {
            logger.error("Global variable '{}' not found! Doing nothing", ENDPOINT_VARIABLE_NAME);
            return;
        }
        
        URIBuilder requestUri = new URIBuilder()
            .setScheme(ENDPOINT_SCHEME)
            .setHost(ENDPOINT_HOST)
            .setPort(ENDPOINT_PORT)
            .setPath(getHandlerEndpoint())
            .addParameter("releaseId", release.getId())
            .addParameter("onFailureUser", ENDPOINT_USER);
        HttpGet request = new HttpGet(requestUri.build());
        // without this, Apache HC will only send auth *after* a failure
        HttpClientContext authenticatingContext = HttpClientContext.create();
        authenticatingContext.setAuthCache(PREEMPTIVE_AUTH_CACHE);
        logger.debug("About to execute callback to {}", request);
        CloseableHttpResponse response =
            HTTP_CLIENT.execute(request, authenticatingContext);
        try {
            logger.info("Response line from request: {}", 
                response.getStatusLine());
            logger.debug("Response body: {}",
                EntityUtils.toString(response.getEntity()));
        } finally {
            response.close();
        }
    }
}
