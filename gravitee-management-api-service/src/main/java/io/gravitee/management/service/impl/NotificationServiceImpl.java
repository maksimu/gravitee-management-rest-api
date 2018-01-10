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
package io.gravitee.management.service.impl;

import io.gravitee.common.utils.UUID;
import io.gravitee.management.model.NewNotificationEntity;
import io.gravitee.management.model.NotificationEntity;
import io.gravitee.management.service.NotificationService;
import io.gravitee.management.service.exceptions.TechnicalManagementException;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.NotificationRepository;
import io.gravitee.repository.management.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class NotificationServiceImpl extends AbstractService implements NotificationService {

    private final Logger LOGGER = LoggerFactory.getLogger(NotificationServiceImpl.class);

    @Autowired
    private NotificationRepository notificationRepository;

    @Override
    public List<NotificationEntity> findByUsername(String username) {
        try {
            return notificationRepository.findByUsername(username).stream().map(this::convert).collect(Collectors.toList());
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find notifications by username {}", username, ex);
            throw new TechnicalManagementException("An error occurs while trying to find notifications by username " + username, ex);
        }
    }

    @Override
    public NotificationEntity create(NewNotificationEntity notificationEntity) {
        Notification notification = convert(notificationEntity);
        notification.setId(UUID.toString(UUID.random()));
        notification.setCreatedAt(new Date());
        try {
            return convert(notificationRepository.create(notification));
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to create {}", notification, ex);
            throw new TechnicalManagementException("An error occurs while trying create " + notification, ex);
        }
    }

    @Override
    public void delete(String notificationId) {
        try {
            notificationRepository.delete(notificationId);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to delete {}", notificationId, ex);
            throw new TechnicalManagementException("An error occurs while trying delete " + notificationId, ex);
        }
    }

    private Notification convert(NewNotificationEntity entity) {
        Notification notification = new Notification();
        notification.setTitle(entity.getTitle());
        notification.setMessage(entity.getMessage());
        notification.setUsername(entity.getMessage());
        return notification;
    }

    private NotificationEntity convert(Notification notification) {
        NotificationEntity entity = new NotificationEntity();
        entity.setId(notification.getId());
        entity.setTitle(notification.getTitle());
        entity.setMessage(notification.getMessage());
        entity.setCreateAt(notification.getCreatedAt());
        return entity;
    }
}
