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
import io.gravitee.management.model.*;
import io.gravitee.management.model.permissions.SystemRole;
import io.gravitee.management.service.*;
import io.gravitee.management.service.exceptions.ApplicationAlreadyExistsException;
import io.gravitee.management.service.exceptions.ApplicationNotFoundException;
import io.gravitee.management.service.exceptions.SubscriptionNotClosableException;
import io.gravitee.management.service.exceptions.TechnicalManagementException;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.ApplicationRepository;
import io.gravitee.repository.management.api.MembershipRepository;
import io.gravitee.repository.management.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static io.gravitee.repository.management.model.Application.AuditEvent.APPLICATION_ARCHIVED;
import static io.gravitee.repository.management.model.Application.AuditEvent.APPLICATION_CREATED;
import static io.gravitee.repository.management.model.Audit.AuditProperties.METADATA;
import static io.gravitee.repository.management.model.Metadata.AuditEvent.METADATA_DELETED;
import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;


/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class ApplicationServiceImpl extends AbstractService implements ApplicationService {

    private final Logger LOGGER = LoggerFactory.getLogger(ApplicationServiceImpl.class);

    @Autowired
    private ApplicationRepository applicationRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private MembershipRepository membershipRepository;

    @Autowired
    private GroupService groupService;

    @Autowired
    private SubscriptionService subscriptionService;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private AuditService auditService;

    @Override
    public ApplicationEntity findById(String applicationId) {
        try {
            LOGGER.debug("Find application by ID: {}", applicationId);

            Optional<Application> application = applicationRepository.findById(applicationId);

            if (application.isPresent()) {
                Optional<Membership> primaryOwnerMembership = membershipRepository.findByReferenceAndRole(
                        MembershipReferenceType.APPLICATION,
                        applicationId,
                        RoleScope.APPLICATION,
                        SystemRole.PRIMARY_OWNER.name())
                        .stream()
                        .findFirst();
                if (!primaryOwnerMembership.isPresent()) {
                    LOGGER.error("The Application {} doesn't have any primary owner.", applicationId);
                    throw new TechnicalException("The Application " + applicationId + " doesn't have any primary owner.");
                }
                return convert(application.get(), userService.findByName(primaryOwnerMembership.get().getUserId(), false));
            }

            throw new ApplicationNotFoundException(applicationId);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find an application using its ID {}", applicationId, ex);
            throw new TechnicalManagementException("An error occurs while trying to find an application using its ID " + applicationId, ex);
        }
    }

    @Override
    public Set<ApplicationEntity> findByUser(String username) {
        try {
            LOGGER.debug("Find applications for user {}", username);

            //find applications where the user is a member
            List<String> appIds = membershipRepository.findByUserAndReferenceType(username, MembershipReferenceType.APPLICATION).stream()
                    .map(Membership::getReferenceId).collect(Collectors.toList());
            final Set<Application> applications =
                    applicationRepository.findByIds(appIds).stream().
                            filter(app -> ApplicationStatus.ACTIVE.equals(app.getStatus())).
                            collect(Collectors.toSet());

            //find applications which be part of the same group as the user
            List<String> groupIds = membershipRepository.findByUserAndReferenceType(username, MembershipReferenceType.GROUP).stream()
                    .filter(m -> m.getRoles().keySet().contains(RoleScope.APPLICATION.getId()))
                    .map(Membership::getReferenceId).collect(Collectors.toList());
            applications.addAll(applicationRepository.findByGroups(groupIds, ApplicationStatus.ACTIVE));

            if (applications.isEmpty()) {
                return emptySet();
            }

            return this.convert(applications);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find applications for user {}", username, ex);
            throw new TechnicalManagementException("An error occurs while trying to find applications for user " + username, ex);
        }
    }

    @Override
    public Set<ApplicationEntity> findByName(String name) {
        LOGGER.debug("Find applications by name {}", name);
        try {
            if (name == null || name.trim().isEmpty()) {
                return emptySet();
            }
            Set<Application> applications = applicationRepository.
                    findByName(name.trim()).stream().
                    filter(app -> ApplicationStatus.ACTIVE.equals(app.getStatus())).
                    collect(Collectors.toSet());
            return convert(applications);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find applications for name {}", name, ex);
            throw new TechnicalManagementException("An error occurs while trying to find applications for name " + name, ex);
        }
    }

    @Override
    public Set<ApplicationEntity> findByGroup(String groupId) {
        LOGGER.debug("Find applications by group {}", groupId);
        try {
            return convert(applicationRepository.findByGroups(Collections.singletonList(groupId), ApplicationStatus.ACTIVE));
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find applications for group {}", groupId, ex);
            throw new TechnicalManagementException("An error occurs while trying to find applications for group " + groupId, ex);
        }
    }

    @Override
    public Set<ApplicationEntity> findAll() {
        try {
            LOGGER.debug("Find all applications");

            final Set<Application> applications = applicationRepository.findAll(ApplicationStatus.ACTIVE);

            if (applications == null || applications.isEmpty()) {
                return emptySet();
            }

            return this.convert(applications);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find all applications", ex);
            throw new TechnicalManagementException("An error occurs while trying to find all applications", ex);
        }
    }

    @Override
    public ApplicationEntity create(NewApplicationEntity newApplicationEntity, String username) {
        try {
            LOGGER.debug("Create {} for user {}", newApplicationEntity, username);

            if (newApplicationEntity.getGroups() != null && !newApplicationEntity.getGroups().isEmpty()) {
                //throw a NotFoundException if the group doesn't exist
                groupService.findByIds(newApplicationEntity.getGroups());
            }
            String id = UUID.toString(UUID.random());

            Optional<Application> checkApplication = applicationRepository.findById(id);
            if (checkApplication.isPresent()) {
                throw new ApplicationAlreadyExistsException(id);
            }

            Application application = convert(newApplicationEntity);

            application.setId(id);
            application.setStatus(ApplicationStatus.ACTIVE);

            // Add Default groups
            Set<String> defaultGroups = groupService.findByEvent(GroupEvent.APPLICATION_CREATE).
                    stream().
                    map(GroupEntity::getId).
                    collect(Collectors.toSet());
            if (!defaultGroups.isEmpty() && application.getGroups() == null) {
                application.setGroups(defaultGroups);
            } else if (!defaultGroups.isEmpty()) {
                application.getGroups().addAll(defaultGroups);
            }

            // Set date fields
            application.setCreatedAt(new Date());
            application.setUpdatedAt(application.getCreatedAt());

            Application createdApplication = applicationRepository.create(application);
            // Audit
            auditService.createApplicationAuditLog(
                    createdApplication.getId(),
                    Collections.emptyMap(),
                    APPLICATION_CREATED,
                    isAuthenticated()?getAuthenticatedUsername():username,
                    createdApplication.getCreatedAt(),
                    null,
                    createdApplication);

            // Add the primary owner of the newly created API
            Membership membership = new Membership(username, createdApplication.getId(), MembershipReferenceType.APPLICATION);
            membership.setRoles(singletonMap(RoleScope.APPLICATION.getId(), SystemRole.PRIMARY_OWNER.name()));
            membership.setCreatedAt(application.getCreatedAt());
            membership.setUpdatedAt(application.getCreatedAt());
            membershipRepository.create(membership);
            //TODO add membership log
            return convert(createdApplication, userService.findByName(username, false));
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to create {} for user {}", newApplicationEntity, username, ex);
            throw new TechnicalManagementException("An error occurs while trying create " + newApplicationEntity + " for user " + username, ex);
        }
    }

    @Override
    public ApplicationEntity update(String applicationId, UpdateApplicationEntity updateApplicationEntity) {
        try {
            LOGGER.debug("Update application {}", applicationId);
            if (updateApplicationEntity.getGroups() != null && !updateApplicationEntity.getGroups().isEmpty()) {
                //throw a NotFoundException if the group doesn't exist
                groupService.findByIds(updateApplicationEntity.getGroups());
            }
            Optional<Application> optApplicationToUpdate = applicationRepository.findById(applicationId);
            if (!optApplicationToUpdate.isPresent()) {
                throw new ApplicationNotFoundException(applicationId);
            }

            Application application = convert(updateApplicationEntity);
            application.setId(applicationId);
            application.setStatus(ApplicationStatus.ACTIVE);
            application.setCreatedAt(optApplicationToUpdate.get().getCreatedAt());
            application.setUpdatedAt(new Date());

            Application updatedApplication =  applicationRepository.update(application);
            // Audit
            auditService.createApplicationAuditLog(
                    updatedApplication.getId(),
                    Collections.emptyMap(),
                    APPLICATION_CREATED,
                    updatedApplication.getUpdatedAt(),
                    optApplicationToUpdate.get(),
                    updatedApplication);
            return convert(Collections.singleton(updatedApplication)).iterator().next();
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to update application {}", applicationId, ex);
            throw new TechnicalManagementException(String.format(
                    "An error occurs while trying to update application %s", applicationId), ex);
        }
    }

    @Override
    public void archive(String applicationId) {
        try {
            LOGGER.debug("Delete application {}", applicationId);
            Optional<Application> optApplication = applicationRepository.findById(applicationId);

            if(!optApplication.isPresent()) {
                throw new ApplicationNotFoundException(applicationId);
            }
            Application application = optApplication.get();
            Application previousApplication = new Application(application);
            Set<SubscriptionEntity> subscriptions = subscriptionService.findByApplicationAndPlan(applicationId, null);

            subscriptions.forEach(subscription -> {
                Set<ApiKeyEntity> apiKeys = apiKeyService.findBySubscription(subscription.getId());
                apiKeys.forEach(apiKey -> {
                    try {
                        apiKeyService.delete(apiKey.getKey());
                    } catch (TechnicalManagementException tme) {
                        LOGGER.error("An error occurs while deleting API Key {}", apiKey.getKey(), tme);
                    }
                });

                try {
                    subscriptionService.close(subscription.getId());
                } catch (SubscriptionNotClosableException snce) {
                    // Subscription can not be closed because it is already closed or not yet accepted
                    LOGGER.debug("The subscription can not be closed: {}", snce.getMessage());
                }
            });

            // Archive the application
            application.setUpdatedAt(new Date());
            application.setStatus(ApplicationStatus.ARCHIVED);
            applicationRepository.update(application);
            // Audit
            auditService.createApplicationAuditLog(
                    application.getId(),
                    Collections.emptyMap(),
                    APPLICATION_ARCHIVED,
                    application.getUpdatedAt(),
                    previousApplication,
                    application);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to delete application {}", applicationId, ex);
            throw new TechnicalManagementException(String.format(
                    "An error occurs while trying to delete application %s", applicationId), ex);
        }
    }

    private Set<ApplicationEntity> convert(Set<Application> applications) throws TechnicalException {
        if (applications == null || applications.isEmpty()){
            return Collections.emptySet();
        }
        //find primary owners usernames of each applications
        Set<Membership> memberships = membershipRepository.findByReferencesAndRole(
                MembershipReferenceType.APPLICATION,
                applications.stream().map(Application::getId).collect(Collectors.toList()),
                RoleScope.APPLICATION,
                SystemRole.PRIMARY_OWNER.name()
        );

        int poMissing = applications.size() - memberships.size();
        if (poMissing > 0) {
            Optional<String> optionalApplicationsAsString = applications.stream().map(Application::getId).reduce((a, b) -> a + " / " + b);
            String applicationsAsString = "?";
            if (optionalApplicationsAsString.isPresent())
                applicationsAsString = optionalApplicationsAsString.get();
            LOGGER.error("{} applications has no identified primary owners in this list {}.", poMissing , applicationsAsString);
            throw new TechnicalManagementException(poMissing + " applications has no identified primary owners in this list " + applicationsAsString + ".");
        }

        Map<String, String> applicationToUser = new HashMap<>(memberships.size());
        memberships.forEach(membership -> applicationToUser.put(membership.getReferenceId(), membership.getUserId()));

        Map<String, UserEntity> userIdToUserEntity = new HashMap<>(memberships.size());
        userService.findByNames(memberships.stream().map(Membership::getUserId).collect(Collectors.toList()), false)
                .forEach(userEntity -> userIdToUserEntity.put(userEntity.getUsername(), userEntity));

        return applications.stream()
                .map(publicApplication -> convert(publicApplication, userIdToUserEntity.get(applicationToUser.get(publicApplication.getId()))))
                .collect(Collectors.toSet());
    }

    private ApplicationEntity convert(Application application, UserEntity primaryOwner) {
        ApplicationEntity applicationEntity = new ApplicationEntity();

        applicationEntity.setId(application.getId());
        applicationEntity.setName(application.getName());
        applicationEntity.setDescription(application.getDescription());
        applicationEntity.setType(application.getType());
        applicationEntity.setStatus(application.getStatus().toString());
        applicationEntity.setGroups(application.getGroups());
        applicationEntity.setCreatedAt(application.getCreatedAt());
        applicationEntity.setUpdatedAt(application.getUpdatedAt());

        if (primaryOwner != null) {
            final PrimaryOwnerEntity primaryOwnerEntity = new PrimaryOwnerEntity();
            primaryOwnerEntity.setUsername(primaryOwner.getUsername());
            primaryOwnerEntity.setLastname(primaryOwner.getLastname());
            primaryOwnerEntity.setFirstname(primaryOwner.getFirstname());
            primaryOwnerEntity.setEmail(primaryOwner.getEmail());
            applicationEntity.setPrimaryOwner(primaryOwnerEntity);
        }

        return applicationEntity;
    }

    private static Application convert(NewApplicationEntity newApplicationEntity) {
        Application application = new Application();

        application.setName(newApplicationEntity.getName().trim());
        application.setDescription(newApplicationEntity.getDescription().trim());
        application.setGroups(newApplicationEntity.getGroups());

        if (newApplicationEntity.getType() != null) {
            application.setType(newApplicationEntity.getType().trim());
        }

        return application;
    }

    private static Application convert(UpdateApplicationEntity updateApplicationEntity) {
        Application application = new Application();

        application.setName(updateApplicationEntity.getName().trim());
        application.setDescription(updateApplicationEntity.getDescription().trim());
        application.setGroups(updateApplicationEntity.getGroups());

        if (updateApplicationEntity.getType() != null) {
            application.setType(updateApplicationEntity.getType().trim());
        }

        return application;
    }

}
