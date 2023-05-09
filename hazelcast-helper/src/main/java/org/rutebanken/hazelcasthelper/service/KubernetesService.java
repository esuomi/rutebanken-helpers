/*
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 *   https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 *
 */

package org.rutebanken.hazelcasthelper.service;

import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class KubernetesService {
    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesService.class);

    private final String kubernetesUrl;

    protected final String namespace;

    private final boolean kubernetesEnabled;

    protected KubernetesClient kube;

    public KubernetesService(String kubernetesUrl, String namespace, boolean kubernetesEnabled) {
        this.kubernetesUrl = kubernetesUrl;
        this.namespace = namespace;
        this.kubernetesEnabled = kubernetesEnabled;
    }

    public KubernetesService(String namespace, boolean kubernetesEnabled) {
        this(null, namespace, kubernetesEnabled);
    }

    @PostConstruct
    public final void init() {
        if (!kubernetesEnabled) {
            LOGGER.warn("Disabling kubernetes connection as rutebanken.kubernetes.enabled={}", kubernetesEnabled);
            return;
        }
        if (kubernetesUrl != null && !"".equals(kubernetesUrl)) {
            LOGGER.info("Connecting to {}", kubernetesUrl);
            Config config = new ConfigBuilder().withMasterUrl("http://localhost:8000/").build();
            kube = new KubernetesClientBuilder().withConfig(config).build();
        } else {
            LOGGER.info("Using default settings, as this should auto-configure correctly in the kubernetes cluster");
            kube = new KubernetesClientBuilder().build();
        }
    }

    @PreDestroy
    public final void end() {
        if (kube != null) {
            kube.close();
        }
    }

    public boolean isKubernetesEnabled() {
        return kubernetesEnabled;
    }

    public List<String> findEndpoints() {
        String serviceName = findDeploymentName();
        LOGGER.info("Shall find endpoints for {}", serviceName);
        return findEndpoints(serviceName);
    }

    /**
     * When running on kubernetes, the deployment name is part of the hostname.
     * TODO It is known that this will fail if the hostname contains dashes. Improve later
     */
    public String findDeploymentName() {
        String hostname = System.getenv("HOSTNAME");
        if (hostname == null) {
            hostname = "localhost";
        }
        int dash = hostname.indexOf('-');
        return dash == -1
                ? hostname
                : hostname.substring(0, dash);
    }

    /**
     * @return Endpoints found for the given service name, both the ready and not ready endpoints
     */
    public List<String> findEndpoints(String serviceName) {
        if (kube == null) {
            return new ArrayList<>();
        }
        Endpoints eps = kube.endpoints().inNamespace(namespace).withName(serviceName).get();
        List<String> ready = addressesFrom(eps, EndpointSubset::getAddresses);
        List<String> notReady = addressesFrom(eps, EndpointSubset::getNotReadyAddresses);
        LOGGER.info("Got {} endpoints and {} NOT ready endpoints", ready.size(), notReady.size());

        List<String> result = new ArrayList<>(ready);
        result.addAll(notReady);
        LOGGER.info("Ended up with the the following endpoints for endpoint {} : {}", serviceName, result);
        return result;
    }

    private List<String> addressesFrom(Endpoints endpoints, Function<EndpointSubset, List<EndpointAddress>> addressFunction) {
        if (endpoints == null || endpoints.getSubsets() == null) {
            return new ArrayList<>();
        }

        return endpoints.getSubsets()
                .stream()
                .map(addressFunction)
                .filter(Objects::nonNull)
                .flatMap(Collection::stream)
                .map(EndpointAddress::getIp)
                .collect(Collectors.toList());
    }
}