/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package jakarta.websocket.server;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;

import jakarta.websocket.Decoder;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Extension;
import jakarta.websocket.HandshakeResponse;

/**
 * Provides configuration information for WebSocket endpoints published to a server. Applications may provide their own
 * implementation or use {@link Builder}.
 */
public interface ServerEndpointConfig extends EndpointConfig {

    Class<?> getEndpointClass();

    /**
     * Returns the path at which this WebSocket server endpoint has been registered. It may be a path or a level 0 URI
     * template.
     *
     * @return The registered path
     */
    String getPath();

    List<String> getSubprotocols();

    List<Extension> getExtensions();

    Configurator getConfigurator();


    final class Builder {

        public static Builder create(Class<?> endpointClass, String path) {
            return new Builder(endpointClass, path);
        }


        private final Class<?> endpointClass;
        private final String path;
        private List<Class<? extends Encoder>> encoders = Collections.emptyList();
        private List<Class<? extends Decoder>> decoders = Collections.emptyList();
        private List<String> subprotocols = Collections.emptyList();
        private List<Extension> extensions = Collections.emptyList();
        private Configurator configurator = Configurator.fetchContainerDefaultConfigurator();


        private Builder(Class<?> endpointClass, String path) {
            if (endpointClass == null) {
                throw new IllegalArgumentException("Endpoint class may not be null");
            }
            if (path == null) {
                throw new IllegalArgumentException("Path may not be null");
            }
            if (path.isEmpty()) {
                throw new IllegalArgumentException("Path may not be empty");
            }
            if (path.charAt(0) != '/') {
                throw new IllegalArgumentException("Path must start with '/'");
            }
            this.endpointClass = endpointClass;
            this.path = path;
        }

        public ServerEndpointConfig build() {
            return new DefaultServerEndpointConfig(endpointClass, path, subprotocols, extensions, encoders, decoders,
                    configurator);
        }


        public Builder encoders(List<Class<? extends Encoder>> encoders) {
            if (encoders == null || encoders.isEmpty()) {
                this.encoders = Collections.emptyList();
            } else {
                this.encoders = Collections.unmodifiableList(encoders);
            }
            return this;
        }


        public Builder decoders(List<Class<? extends Decoder>> decoders) {
            if (decoders == null || decoders.isEmpty()) {
                this.decoders = Collections.emptyList();
            } else {
                this.decoders = Collections.unmodifiableList(decoders);
            }
            return this;
        }


        public Builder subprotocols(List<String> subprotocols) {
            if (subprotocols == null || subprotocols.isEmpty()) {
                this.subprotocols = Collections.emptyList();
            } else {
                this.subprotocols = Collections.unmodifiableList(subprotocols);
            }
            return this;
        }


        public Builder extensions(List<Extension> extensions) {
            if (extensions == null || extensions.isEmpty()) {
                this.extensions = Collections.emptyList();
            } else {
                this.extensions = Collections.unmodifiableList(extensions);
            }
            return this;
        }


        public Builder configurator(Configurator serverEndpointConfigurator) {
            if (serverEndpointConfigurator == null) {
                this.configurator = Configurator.fetchContainerDefaultConfigurator();
            } else {
                this.configurator = serverEndpointConfigurator;
            }
            return this;
        }
    }


    class Configurator {

        private static volatile Configurator defaultImpl = null;
        private static final Object defaultImplLock = new Object();

        private static final String DEFAULT_IMPL_CLASSNAME =
                "org.apache.tomcat.websocket.server.DefaultServerEndpointConfigurator";

        static Configurator fetchContainerDefaultConfigurator() {
            if (defaultImpl == null) {
                synchronized (defaultImplLock) {
                    if (defaultImpl == null) {
                        defaultImpl = loadDefault();
                    }
                }
            }
            return defaultImpl;
        }


        private static Configurator loadDefault() {
            Configurator result = null;

            ServiceLoader<Configurator> serviceLoader = ServiceLoader.load(Configurator.class);

            Iterator<Configurator> iter = serviceLoader.iterator();
            while (result == null && iter.hasNext()) {
                result = iter.next();
            }

            // Fall-back. Also used by unit tests
            if (result == null) {
                try {
                    @SuppressWarnings("unchecked")
                    Class<Configurator> clazz = (Class<Configurator>) Class.forName(DEFAULT_IMPL_CLASSNAME);
                    result = clazz.getConstructor().newInstance();
                } catch (ReflectiveOperationException | IllegalArgumentException | SecurityException e) {
                    // No options left. Just return null.
                }
            }
            return result;
        }


        /**
         * Return the platform default configurator.
         *
         * @return the platform default configurator
         *
         * @since WebSocket 2.1
         */
        public ServerEndpointConfig.Configurator getContainerDefaultConfigurator() {
            return fetchContainerDefaultConfigurator();
        }

        public String getNegotiatedSubprotocol(List<String> supported, List<String> requested) {
            return fetchContainerDefaultConfigurator().getNegotiatedSubprotocol(supported, requested);
        }

        public List<Extension> getNegotiatedExtensions(List<Extension> installed, List<Extension> requested) {
            return fetchContainerDefaultConfigurator().getNegotiatedExtensions(installed, requested);
        }

        public boolean checkOrigin(String originHeaderValue) {
            return fetchContainerDefaultConfigurator().checkOrigin(originHeaderValue);
        }

        public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request, HandshakeResponse response) {
            fetchContainerDefaultConfigurator().modifyHandshake(sec, request, response);
        }

        public <T extends Object> T getEndpointInstance(Class<T> clazz) throws InstantiationException {
            return fetchContainerDefaultConfigurator().getEndpointInstance(clazz);
        }
    }
}
