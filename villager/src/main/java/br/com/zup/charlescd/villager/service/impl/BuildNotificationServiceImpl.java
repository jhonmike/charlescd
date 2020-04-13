/*
 * This Source Code Form is subject to the terms of the
 * Mozilla Public License, v. 2.0. If a copy of the MPL was not distributed
 * with this file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package br.com.zup.charlescd.villager.service.impl;

import br.com.zup.charlescd.villager.infrastructure.integration.GenericRestClient;
import br.com.zup.charlescd.villager.infrastructure.integration.callback.CallbackPayload;
import br.com.zup.charlescd.villager.infrastructure.persistence.*;
import br.com.zup.charlescd.villager.service.BuildNotificationService;
import org.apache.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.transaction.Transactional;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class BuildNotificationServiceImpl implements BuildNotificationService {

    private GenericRestClient restClient;
    private ComponentRepository componentRepository;
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildNotificationServiceImpl.class);

    @Inject
    public BuildNotificationServiceImpl(GenericRestClient restClient, ComponentRepository componentRepository) {
        this.restClient = restClient;
        this.componentRepository = componentRepository;
    }

    @Transactional
    public void notify(BuildEntity buildEntity, List<ModuleEntity> modules) {

        CallbackPayload payload = new CallbackPayload(buildEntity.status.name());

        modules.forEach(moduleEntity -> {

            List<ComponentEntity> componentList = componentRepository.findByModule(moduleEntity.id);

            payload.addModule(CallbackPayload.ModulePart.newBuilder()
                    .withModuleId(moduleEntity.externalId)
                    .withStatus(moduleEntity.status.name())
                    .withComponents(componentList.stream()
                            .map(componentEntity -> new CallbackPayload.ComponentPart.Builder()
                                    .withName(
                                            String.format(
                                                    "%s/%s:%s",
                                                    moduleEntity.registry,
                                                    componentEntity.name,
                                                    componentEntity.tagName
                                            )
                                    )
                                    .withTagName(componentEntity.tagName)
                                    .build())
                            .collect(Collectors.toSet()))
                    .build());
        });

        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

        if (buildEntity.circleId != null) {
            headers.add("X-Circle-Id", buildEntity.circleId);
        }

        Response response;

        try {

            response = this.restClient.doPost(buildEntity.callbackUrl, payload, headers);

        } catch (Exception e) {

            LOGGER.error("Could not send callback request.", e);
            buildEntity.callbackStatus = CallbackStatus.FAILURE;
            buildEntity.persist();

            return;

        }

        if (response.getStatus() != HttpStatus.SC_NO_CONTENT) {

            LOGGER.error(createErrorMessage(buildEntity.callbackUrl, response));
            buildEntity.callbackStatus = CallbackStatus.FAILURE;
            buildEntity.persist();

            return;

        }

        buildEntity.callbackStatus = CallbackStatus.SUCCESS;
        buildEntity.persist();
    }

    private String createErrorMessage(String uri, Response response) {
        return new StringBuilder("Could not send callback request - ")
                .append(uri)
                .append(" - ")
                .append("HttpStatus: ")
                .append(response.getStatus())
                .append(" - ")
                .append(response.readEntity(String.class))
                .toString();
    }

}