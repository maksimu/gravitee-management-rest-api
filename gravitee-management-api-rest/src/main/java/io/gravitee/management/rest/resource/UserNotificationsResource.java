/**
 * Copyright (C) 2015 The Gravitee team (http://gravitee.io)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gravitee.management.rest.resource;

import io.gravitee.common.http.MediaType;
import io.gravitee.management.model.NotificationEntity;
import io.gravitee.management.rest.model.PagedResult;
import io.gravitee.management.service.NotificationService;
import io.swagger.annotations.Api;
import org.springframework.beans.factory.annotation.Autowired;

import javax.ws.rs.*;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Api(tags = {"Notifications"})
public class UserNotificationsResource extends AbstractResource  {

    @Autowired
    private NotificationService notificationService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public PagedResult<NotificationEntity> list()  {
        List<NotificationEntity> notifications = notificationService.findByUsername(getAuthenticatedUsername())
                .stream()
                .sorted(Comparator.comparing(NotificationEntity::getCreateAt))
                .collect(Collectors.toList());

        PagedResult<NotificationEntity> pagedResult = new PagedResult(notifications, Collections.emptyMap());
        PagedResult.Page page = pagedResult.new Page(1, notifications.size(), notifications.size(), 1, notifications.size());
        pagedResult.setPage(page);
        return pagedResult;
    }

    @Path("{notification}")
    @DELETE
    public void delete(@PathParam("notification") String notificationId) {
        notificationService.delete(notificationId);
    }
}
