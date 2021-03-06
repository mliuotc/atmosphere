/*
 * Copyright 2013 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.container;

import org.atmosphere.container.version.JSR356WebSocket;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketEventListener;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRegistration;
import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.MessageHandler;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.atmosphere.cpr.ApplicationConfig.ALLOW_QUERYSTRING_AS_REQUEST;

public class JSR356Endpoint extends Endpoint {

    private static final Logger logger = LoggerFactory.getLogger(JSR356Endpoint.class);

    private final WebSocketProcessor webSocketProcessor;
    private final Integer maxBinaryBufferSize;
    private final Integer maxTextBufferSize;
    private AtmosphereRequest request;
    private final AtmosphereFramework framework;
    private WebSocket webSocket;
    private final int webSocketWriteTimeout;
    private String servletPath = "";
    private HandshakeRequest handshakeRequest;

    public JSR356Endpoint(AtmosphereFramework framework, WebSocketProcessor webSocketProcessor) {
        this.framework = framework;
        this.webSocketProcessor = webSocketProcessor;

        if (framework.isUseNativeImplementation()) {
            throw new IllegalStateException("You cannot use WebSocket native implementation with JSR356. Please set " + ApplicationConfig.PROPERTY_NATIVE_COMETSUPPORT + " to false");
        }

        String s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME);
        if (s != null) {
            webSocketWriteTimeout = Integer.valueOf(s);
        } else {
            webSocketWriteTimeout = -1;
        }

        s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_MAXBINARYSIZE);
        if (s != null) {
            maxBinaryBufferSize = Integer.valueOf(s);
        } else {
            maxBinaryBufferSize = -1;
        }

        s = framework.getAtmosphereConfig().getInitParameter(ApplicationConfig.WEBSOCKET_MAXTEXTSIZE);
        if (s != null) {
            maxTextBufferSize = Integer.valueOf(s);
        } else {
            maxTextBufferSize = -1;
        }

        try {
            Map<String, ? extends ServletRegistration> m = framework.getServletContext().getServletRegistrations();
            for (Map.Entry<String, ? extends ServletRegistration> e : m.entrySet()) {
                if (AtmosphereServlet.class.isAssignableFrom(loadClass(e.getValue().getClassName()))) {
                    // TODO: This is a hack and won't work with several Servlet
                    servletPath = "/" + e.getValue().getMappings().iterator().next().replace("/", "").replace("*", "");
                }
            }
        } catch (Exception ex) {
            logger.trace("", ex);
        }
    }

    public JSR356Endpoint handshakeRequest(HandshakeRequest handshakeRequest) {
        this.handshakeRequest = handshakeRequest;
        return this;
    }

    @Override
    public void onOpen(Session session, EndpointConfig endpointConfig) {

        if (!webSocketProcessor.handshake(request)) {
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.CANNOT_ACCEPT, "Handshake not accepted."));
            } catch (IOException e) {
                logger.trace("", e);
            }
            return;
        }

        if (maxBinaryBufferSize != -1) session.setMaxBinaryMessageBufferSize(maxBinaryBufferSize);
        if (webSocketWriteTimeout != -1) session.setMaxIdleTimeout(webSocketWriteTimeout);
        if (maxTextBufferSize != -1) session.setMaxTextMessageBufferSize(maxTextBufferSize);

        webSocket = new JSR356WebSocket(session, framework.getAtmosphereConfig());

        Map<String, String> headers = new HashMap<String, String>();
        for (Map.Entry<String, List<String>> e : handshakeRequest.getHeaders().entrySet()) {
            headers.put(e.getKey(), e.getValue().size() > 0 ? e.getValue().get(0) : "");
        }

        String pathInfo = "";
        StringBuffer p = new StringBuffer("/");
        try {
            boolean append = true;
            for (Map.Entry<String, String> e : session.getPathParameters().entrySet()) {
                // Glasfish return reverse path!!!
                if (append && !e.getKey().equalsIgnoreCase("{path}")) {
                    append = false;
                }

                if (append) {
                    p.append(e.getValue()).append("/");
                } else {
                    p.insert(0, e.getValue()).insert(0, "/");
                }
            }
            if (p.length() > 1) {
                p.deleteCharAt(p.length() - 1);
            }

            pathInfo = p.toString();
            if (!pathInfo.equals(servletPath) && pathInfo.length() > servletPath.length()) {
                pathInfo = p.toString().substring(servletPath.length());
            } else if (pathInfo.equals(servletPath)) {
                pathInfo = null;
            }
        } catch (Exception ex) {
            logger.warn("Unexpected path decoding", ex);
        }

        try {
            String requestUri = session.getRequestURI().toASCIIString();
            if (requestUri.contains("?")) {
                requestUri = requestUri.substring(0, requestUri.indexOf("?"));
            }
            request = new AtmosphereRequest.Builder()
                    .requestURI(requestUri)
                    .requestURL(requestUri)
                    .headers(headers)
                    .session((HttpSession) handshakeRequest.getHttpSession())
                    .servletPath(servletPath)
                    .contextPath(framework.getServletContext().getContextPath())
                    .pathInfo(pathInfo)
                    .userPrincipal(session.getUserPrincipal())
                    .build()
                    .queryString(session.getQueryString());


            // TODO: Fix this crazy code.
            framework.addInitParameter(ALLOW_QUERYSTRING_AS_REQUEST, "false");

            webSocketProcessor.open(webSocket, request, AtmosphereResponse.newInstance(framework.getAtmosphereConfig(), request, webSocket));

            framework.addInitParameter(ALLOW_QUERYSTRING_AS_REQUEST, "true");

        } catch (Throwable e) {
            try {
                session.close(new CloseReason(CloseReason.CloseCodes.UNEXPECTED_CONDITION, e.getMessage()));
            } catch (IOException e1) {
                logger.trace("", e);
            }
            logger.error("", e);
            return;
        }

        session.addMessageHandler(new MessageHandler.Whole<String>() {
            @Override
            public void onMessage(String s) {
                webSocketProcessor.invokeWebSocketProtocol(webSocket, s);
            }
        });

        session.addMessageHandler(new MessageHandler.Whole<ByteBuffer>() {
            @Override
            public void onMessage(ByteBuffer bb) {
                byte[] b = new byte[bb.limit()];
                bb.get(b);
                webSocketProcessor.invokeWebSocketProtocol(webSocket, b, 0, b.length);
            }
        });
    }

    @Override
    public void onClose(javax.websocket.Session session, javax.websocket.CloseReason closeCode) {
        logger.trace("{} closed {}", session, closeCode);
        request.destroy();
        webSocketProcessor.close(webSocket, closeCode.getCloseCode().getCode());
    }

    @Override
    public void onError(javax.websocket.Session session, java.lang.Throwable t) {
        logger.error("", t);
        webSocketProcessor.notifyListener(webSocket,
                new WebSocketEventListener.WebSocketEvent<Throwable>(t, WebSocketEventListener.WebSocketEvent.TYPE.EXCEPTION, webSocket));
    }

    protected Class<?> loadClass(String className) throws Exception {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            return getClass().getClassLoader().loadClass(className);
        }
    }
}
