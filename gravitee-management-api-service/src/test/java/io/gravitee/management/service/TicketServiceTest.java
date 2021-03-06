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
package io.gravitee.management.service;

import com.google.common.collect.ImmutableMap;
import io.gravitee.management.model.ApiModelEntity;
import io.gravitee.management.model.ApplicationEntity;
import io.gravitee.management.model.NewTicketEntity;
import io.gravitee.management.model.UserEntity;
import io.gravitee.management.service.builder.EmailNotificationBuilder;
import io.gravitee.management.service.exceptions.EmailRequiredException;
import io.gravitee.management.service.exceptions.SupportUnavailableException;
import io.gravitee.management.service.impl.TicketServiceImpl;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.HashMap;
import java.util.Map;

import static io.gravitee.management.service.builder.EmailNotificationBuilder.EmailTemplate.SUPPORT_TICKET;
import static io.gravitee.management.service.impl.InitializerServiceImpl.DEFAULT_METADATA_EMAIL_SUPPORT;
import static io.gravitee.management.service.impl.InitializerServiceImpl.METADATA_EMAIL_SUPPORT_KEY;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * @author Azize ELAMRANI (azize at graviteesource.com)
 * @author GraviteeSource Team
 */
@RunWith(MockitoJUnitRunner.class)
public class TicketServiceTest {

    private static final String USERNAME = "my-username";
    private static final String USER_EMAIL = "my@email.com";
    private static final String USER_FIRSTNAME = "Firstname";
    private static final String USER_LASTNAME = "Lastname";
    private static final String API_ID = "my-api-id";
    private static final String APPLICATION_ID = "my-application-id";
    private static final String EMAIL_SUBJECT = "email-subject";
    private static final String EMAIL_CONTENT = "Email\nContent";
    private static final boolean EMAIL_COPY_TO_SENDER = false;
    private static final String EMAIL_SUPPORT = "email@support.com";

    @InjectMocks
    private TicketService ticketService = new TicketServiceImpl();

    @Mock
    private UserService userService;
    @Mock
    private MetadataService metadataService;
    @Mock
    private ApiService apiService;
    @Mock
    private ApplicationService applicationService;
    @Mock
    private EmailService emailService;

    @Mock
    private NewTicketEntity newTicketEntity;
    @Mock
    private UserEntity user;
    @Mock
    private ApiModelEntity api;
    @Mock
    private ApplicationEntity application;

    @Test(expected = SupportUnavailableException.class)
    public void shouldNotCreateIfSupportDisabled() {
        setField(ticketService, "enabled", false);

        ticketService.create(USERNAME, newTicketEntity);
    }

    @Test(expected = EmailRequiredException.class)
    public void shouldNotCreateIfUserEmailIsMissing() {
        setField(ticketService, "enabled", true);

        when(userService.findByName(USERNAME, false)).thenReturn(user);

        ticketService.create(USERNAME, newTicketEntity);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldNotCreateIfDefaultEmailSupportIsMissing() {
        setField(ticketService, "enabled", true);

        when(userService.findByName(USERNAME, false)).thenReturn(user);
        when(user.getEmail()).thenReturn(USER_EMAIL);
        when(newTicketEntity.getApi()).thenReturn(API_ID);
        when(apiService.findByIdForTemplates(API_ID)).thenReturn(api);

        ticketService.create(USERNAME, newTicketEntity);
    }

    @Test(expected = IllegalStateException.class)
    public void shouldNotCreateIfDefaultEmailSupportHasNotBeenChanged() {
        setField(ticketService, "enabled", true);

        when(newTicketEntity.getApi()).thenReturn(API_ID);
        when(newTicketEntity.getSubject()).thenReturn(EMAIL_SUBJECT);
        when(newTicketEntity.isCopyToSender()).thenReturn(EMAIL_COPY_TO_SENDER);
        when(newTicketEntity.getContent()).thenReturn(EMAIL_CONTENT);

        when(userService.findByName(USERNAME, false)).thenReturn(user);
        when(user.getEmail()).thenReturn(USER_EMAIL);
        when(apiService.findByIdForTemplates(API_ID)).thenReturn(api);

        final Map<String, String> metadata = new HashMap<>();
        metadata.put(METADATA_EMAIL_SUPPORT_KEY, DEFAULT_METADATA_EMAIL_SUPPORT);
        when(api.getMetadata()).thenReturn(metadata);

        ticketService.create(USERNAME, newTicketEntity);
    }

    @Test
    public void shouldCreateWithApi() {
        setField(ticketService, "enabled", true);

        when(newTicketEntity.getApi()).thenReturn(API_ID);
        when(newTicketEntity.getApplication()).thenReturn(APPLICATION_ID);
        when(newTicketEntity.getSubject()).thenReturn(EMAIL_SUBJECT);
        when(newTicketEntity.isCopyToSender()).thenReturn(EMAIL_COPY_TO_SENDER);
        when(newTicketEntity.getContent()).thenReturn(EMAIL_CONTENT);

        when(userService.findByName(USERNAME, false)).thenReturn(user);
        when(user.getEmail()).thenReturn(USER_EMAIL);
        when(user.getFirstname()).thenReturn(USER_FIRSTNAME);
        when(user.getLastname()).thenReturn(USER_LASTNAME);
        when(apiService.findByIdForTemplates(API_ID)).thenReturn(api);
        when(applicationService.findById(APPLICATION_ID)).thenReturn(application);

        final Map<String, String> metadata = new HashMap<>();
        metadata.put(METADATA_EMAIL_SUPPORT_KEY, EMAIL_SUPPORT);
        when(api.getMetadata()).thenReturn(metadata);

        ticketService.create(USERNAME, newTicketEntity);

        verify(emailService).sendEmailNotification(
                new EmailNotificationBuilder()
                        .from(USER_EMAIL)
                        .fromName(USER_FIRSTNAME + ' ' + USER_LASTNAME)
                        .to(EMAIL_SUPPORT)
                        .subject(EMAIL_SUBJECT)
                        .copyToSender(EMAIL_COPY_TO_SENDER)
                        .template(SUPPORT_TICKET)
                        .params(ImmutableMap.of("user", user, "api", api, "content", "Email<br />Content", "application", application))
                        .build());
    }
}
