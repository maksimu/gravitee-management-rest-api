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

import com.auth0.jwt.JWTSigner;
import com.auth0.jwt.JWTVerifier;
import com.google.common.collect.ImmutableMap;
import io.gravitee.management.model.*;
import io.gravitee.management.service.*;
import io.gravitee.management.service.builder.EmailNotificationBuilder;
import io.gravitee.management.service.common.JWTHelper.Claims;
import io.gravitee.management.service.exceptions.DefaultRoleNotFoundException;
import io.gravitee.management.service.exceptions.TechnicalManagementException;
import io.gravitee.management.service.exceptions.UserNotFoundException;
import io.gravitee.management.service.exceptions.UsernameAlreadyExistsException;
import io.gravitee.repository.exceptions.TechnicalException;
import io.gravitee.repository.management.api.UserRepository;
import io.gravitee.repository.management.model.MembershipDefaultReferenceId;
import io.gravitee.repository.management.model.MembershipReferenceType;
import io.gravitee.repository.management.model.RoleScope;
import io.gravitee.repository.management.model.User;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

import static io.gravitee.management.service.common.JWTHelper.DefaultValues.DEFAULT_JWT_EMAIL_REGISTRATION_EXPIRE_AFTER;
import static io.gravitee.management.service.common.JWTHelper.DefaultValues.DEFAULT_JWT_ISSUER;
import static io.gravitee.repository.management.model.Audit.AuditProperties.USER;

/**
 * @author David BRASSELY (david.brassely at graviteesource.com)
 * @author Nicolas GERAUD (nicolas.geraud at graviteesource.com)
 * @author Azize Elamrani (azize.elamrani at graviteesource.com)
 * @author GraviteeSource Team
 */
@Component
public class UserServiceImpl extends AbstractService implements UserService {

    private final Logger LOGGER = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private EmailService emailService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private RoleService roleService;

    @Autowired
    private MembershipService membershipService;

    @Autowired
    private AuditService auditService;

    @Value("${user.login.defaultApplication:true}")
    private boolean defaultApplicationForFirstConnection;

    private PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public UserEntity connect(String username) {
        try {
            LOGGER.debug("Connection of {}", username);
            Optional<User> checkUser = userRepository.findByUsername(username);
            if (!checkUser.isPresent()) {
                throw new UserNotFoundException(username);
            }

            User user = checkUser.get();
            User previousUser = new User(user);
            // First connection: create default application for user
            if (defaultApplicationForFirstConnection && user.getLastConnectionAt() == null) {
                LOGGER.debug("Create a default application for {}", username);
                NewApplicationEntity defaultApp = new NewApplicationEntity();
                defaultApp.setName("Default application");
                defaultApp.setDescription("My default application");
                applicationService.create(defaultApp, username);
            }

            // Set date fields
            user.setLastConnectionAt(new Date());
            user.setUpdatedAt(user.getLastConnectionAt());

            User updatedUser = userRepository.update(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, username),
                    User.AuditEvent.USER_CONNECTED,
                    user.getUpdatedAt(),
                    previousUser,
                    user);
            return convert(updatedUser, true);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to connect {}", username, ex);
            throw new TechnicalManagementException("An error occurs while trying to connect " + username, ex);
        }
    }

    @Override
    public UserEntity findByName(String username, boolean loadRoles) {
        try {
            LOGGER.debug("Find user by name: {}", username);

            Optional<User> optionalUser = userRepository.findByUsername(username);

            if (optionalUser.isPresent()) {
                return convert(optionalUser.get(), loadRoles);
            }
            //should never happen
            throw new UserNotFoundException(username);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find user using its username {}", username, ex);
            throw new TechnicalManagementException("An error occurs while trying to find user using its username " + username, ex);
        }
    }

    @Override
    public Set<UserEntity> findByNames(List<String> usernames, boolean loadRoles) {
        try {
            LOGGER.debug("Find user by names: {}", usernames);

            Set<User> users = userRepository.findByUsernames(usernames);

            if (!users.isEmpty()) {
                return users.stream().map(u -> this.convert(u, loadRoles)).collect(Collectors.toSet());
            }

            Optional<String> usernamesAsString = usernames.stream().reduce((a, b) -> a + "/" + b);
            if (usernamesAsString.isPresent()) {
                throw new UserNotFoundException(usernamesAsString.get());
            } else {
                throw new UserNotFoundException("?");
            }
        } catch (TechnicalException ex) {
            Optional<String> usernamesAsString = usernames.stream().reduce((a, b) -> a + "/" + b);
            LOGGER.error("An error occurs while trying to find users using their names {}", usernamesAsString, ex);
            throw new TechnicalManagementException("An error occurs while trying to find users using their names " + usernamesAsString, ex);
        }
    }

    private void checkUserRegistrationEnabled() {
        if (!environment.getProperty("user.creation.enabled", Boolean.class, false)) {
            throw new IllegalStateException("The user registration is disabled");
        }
    }

    /**
     * Allows to complete the creation of a user which is pre-created.
     * @param registerUserEntity a valid token and a password
     * @return the user
     */
    @Override
    public UserEntity create(final RegisterUserEntity registerUserEntity) {
        checkUserRegistrationEnabled();
        try {
            final String jwtSecret = environment.getProperty("jwt.secret");
            if (jwtSecret == null || jwtSecret.isEmpty()) {
                throw new IllegalStateException("JWT secret is mandatory");
            }

            final Map<String, Object> claims = new JWTVerifier(jwtSecret).verify(registerUserEntity.getToken());

            final NewUserEntity newUserEntity = new NewUserEntity();
            newUserEntity.setUsername(claims.get(Claims.SUBJECT).toString());
            newUserEntity.setEmail(claims.get(Claims.EMAIL).toString());
            newUserEntity.setFirstname(claims.get(Claims.FIRSTNAME).toString());
            newUserEntity.setLastname(claims.get(Claims.LASTNAME).toString());
            newUserEntity.setPassword(registerUserEntity.getPassword());

            LOGGER.debug("Create an internal user {}", newUserEntity);
            Optional<User> checkUser = userRepository.findByUsername(newUserEntity.getUsername());
            if (checkUser.isPresent() && StringUtils.isNotBlank(checkUser.get().getPassword())) {
                throw new UsernameAlreadyExistsException(newUserEntity.getUsername());
            }

            User user = convert(newUserEntity);

            // Encrypt password if internal user
            if (user.getPassword() != null) {
                user.setPassword(passwordEncoder.encode(user.getPassword()));
            }

            // Set date fields
            user.setUpdatedAt(new Date());
            user = userRepository.update(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, user.getUsername()),
                    User.AuditEvent.USER_CREATED,
                    user.getUpdatedAt(),
                    null,
                    user);
            return convert(user, true);
        } catch (Exception ex) {
            LOGGER.error("An error occurs while trying to create an internal user with the token {}", registerUserEntity.getToken(), ex);
            throw new TechnicalManagementException(ex.getMessage(), ex);
        }
    }

    /**
     * Allows to pre-create a user.
     * @param newExternalUserEntity
     * @return
     */
    @Override
    public UserEntity create(NewExternalUserEntity newExternalUserEntity, boolean addDefaultRole) {
        try {
            LOGGER.debug("Create an external user {}", newExternalUserEntity);
            Optional<User> checkUser = userRepository.findByUsername(newExternalUserEntity.getUsername());
            if (checkUser.isPresent()) {
                throw new UsernameAlreadyExistsException(newExternalUserEntity.getUsername());
            }

            User user = convert(newExternalUserEntity);

            // Set date fields
            user.setCreatedAt(new Date());
            user.setUpdatedAt(user.getCreatedAt());

            User createdUser = userRepository.create(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, user.getUsername()),
                    User.AuditEvent.USER_CREATED,
                    user.getCreatedAt(),
                    null,
                    user);
            if (addDefaultRole) {
                addDefaultMembership(createdUser);
            }

            return convert(createdUser, true);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to create an external user {}", newExternalUserEntity, ex);
            throw new TechnicalManagementException("An error occurs while trying to create an external user" + newExternalUserEntity, ex);
        }
    }

    private void addDefaultMembership(User user) {
        RoleScope[] scopes = {RoleScope.MANAGEMENT, RoleScope.PORTAL};
        List<RoleEntity> defaultRoleByScopes = roleService.findDefaultRoleByScopes(scopes);
        if (defaultRoleByScopes == null || defaultRoleByScopes.isEmpty()) {
            throw new DefaultRoleNotFoundException(scopes);
        }

        for (RoleEntity defaultRoleByScope : defaultRoleByScopes) {
            switch (defaultRoleByScope.getScope()) {
                case MANAGEMENT:
                    membershipService.addOrUpdateMember(
                            MembershipReferenceType.MANAGEMENT,
                            MembershipDefaultReferenceId.DEFAULT.name(),
                            user.getUsername(),
                            RoleScope.MANAGEMENT,
                            defaultRoleByScope.getName());
                    break;
                case PORTAL:
                    membershipService.addOrUpdateMember(
                            MembershipReferenceType.PORTAL,
                            MembershipDefaultReferenceId.DEFAULT.name(),
                            user.getUsername(),
                            RoleScope.PORTAL,
                            defaultRoleByScope.getName());
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Allows to pre-create a user and send an email notification to finalize its creation.
     */
    @Override
    public UserEntity register(final NewExternalUserEntity newExternalUserEntity) {
        checkUserRegistrationEnabled();

        newExternalUserEntity.setUsername(newExternalUserEntity.getEmail());
        newExternalUserEntity.setSource("gravitee");
        newExternalUserEntity.setSourceId(newExternalUserEntity.getUsername());

        final UserEntity userEntity = create(newExternalUserEntity, true);

        // generate a JWT to store user's information and for security purpose
        final Map<String, Object> claims = new HashMap<>();
        claims.put(Claims.ISSUER, environment.getProperty("jwt.issuer", DEFAULT_JWT_ISSUER));

        claims.put(Claims.SUBJECT, userEntity.getUsername());
        claims.put(Claims.EMAIL, userEntity.getEmail());
        claims.put(Claims.FIRSTNAME, userEntity.getFirstname());
        claims.put(Claims.LASTNAME, userEntity.getLastname());

        final JWTSigner.Options options = new JWTSigner.Options();
        options.setExpirySeconds(environment.getProperty("user.creation.token.expire-after",
                Integer.class, DEFAULT_JWT_EMAIL_REGISTRATION_EXPIRE_AFTER));
        options.setIssuedAt(true);
        options.setJwtId(true);

        // send a confirm email with the token
        final String jwtSecret = environment.getProperty("jwt.secret");
        if (jwtSecret == null || jwtSecret.isEmpty()) {
            throw new IllegalStateException("JWT secret is mandatory");
        }

        final String token = new JWTSigner(jwtSecret).sign(claims, options);
        String portalUrl = environment.getProperty("portalURL");

        if (portalUrl.endsWith("/")) {
            portalUrl = portalUrl.substring(0, portalUrl.length() - 1);
        }

        String registrationUrl = portalUrl + "/#!/registration/confirm/" + token;

        emailService.sendAsyncEmailNotification(new EmailNotificationBuilder()
                .to(userEntity.getEmail())
                .subject("User registration - " + userEntity.getUsername())
                .template(EmailNotificationBuilder.EmailTemplate.USER_REGISTRATION)
                .params(ImmutableMap.of(
                        "username", userEntity.getUsername(),
                        "token", token,
                        "registrationUrl", registrationUrl,
                        "user", userEntity))
                .build()
        );

        return userEntity;
    }

    @Override
    public UserEntity update(UpdateUserEntity updateUserEntity) {
        try {
            LOGGER.debug("Updating {}", updateUserEntity);
            Optional<User> checkUser = userRepository.findByUsername(updateUserEntity.getUsername());
            if (!checkUser.isPresent()) {
                throw new UserNotFoundException(updateUserEntity.getUsername());
            }

            User user = checkUser.get();
            User previousUser = new User(user);

            // Set date fields
            user.setUpdatedAt(new Date());

            // Set variant fields
            user.setPicture(updateUserEntity.getPicture());

            User updatedUser = userRepository.update(user);
            auditService.createPortalAuditLog(
                    Collections.singletonMap(USER, user.getUsername()),
                    User.AuditEvent.USER_UPDATED,
                    user.getUpdatedAt(),
                    previousUser,
                    user);
            return convert(updatedUser, true);
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to update {}", updateUserEntity, ex);
            throw new TechnicalManagementException("An error occurs while trying update " + updateUserEntity, ex);
        }
    }

    @Override
    public Set<UserEntity> findAll(boolean loadRoles) {
        try {
            LOGGER.debug("Find all users");

            Set<User> users = userRepository.findAll();

            return users.stream().map(u -> convert(u, loadRoles)).collect(Collectors.toSet());
        } catch (TechnicalException ex) {
            LOGGER.error("An error occurs while trying to find all users", ex);
            throw new TechnicalManagementException("An error occurs while trying to find all users", ex);
        }
    }

    public boolean isDefaultApplicationForFirstConnection() {
        return defaultApplicationForFirstConnection;
    }

    public void setDefaultApplicationForFirstConnection(boolean defaultApplicationForFirstConnection) {
        this.defaultApplicationForFirstConnection = defaultApplicationForFirstConnection;
    }

    private static User convert(NewUserEntity newUserEntity) {
        if (newUserEntity == null) {
            return null;
        }
        User user = new User();

        user.setUsername(newUserEntity.getUsername());
        user.setEmail(newUserEntity.getEmail());
        user.setFirstname(newUserEntity.getFirstname());
        user.setLastname(newUserEntity.getLastname());
        user.setPassword(newUserEntity.getPassword());

        return user;
    }

    private static User convert(NewExternalUserEntity newExternalUserEntity) {
        if (newExternalUserEntity == null) {
            return null;
        }
        User user = new User();

        user.setUsername(newExternalUserEntity.getUsername());
        user.setEmail(newExternalUserEntity.getEmail());
        user.setFirstname(newExternalUserEntity.getFirstname());
        user.setLastname(newExternalUserEntity.getLastname());
        user.setSource(newExternalUserEntity.getSource());
        user.setSourceId(newExternalUserEntity.getSourceId());

        return user;
    }

    private UserEntity convert(User user, boolean loadRoles) {
        if (user == null) {
            return null;
        }
        UserEntity userEntity = new UserEntity();

        userEntity.setSource(user.getSource());
        userEntity.setSourceId(user.getSourceId());
        userEntity.setUsername(user.getUsername());
        userEntity.setEmail(user.getEmail());
        userEntity.setFirstname(user.getFirstname());
        userEntity.setLastname(user.getLastname());
        userEntity.setPassword(user.getPassword());
        userEntity.setCreatedAt(user.getCreatedAt());
        userEntity.setUpdatedAt(user.getUpdatedAt());
        userEntity.setLastConnectionAt(user.getLastConnectionAt());
        userEntity.setPicture(user.getPicture());

        if(loadRoles) {
            Set<UserRoleEntity> roles = new HashSet<>();
            RoleEntity roleEntity = membershipService.getRole(
                    MembershipReferenceType.PORTAL,
                    MembershipDefaultReferenceId.DEFAULT.name(),
                    user.getUsername(),
                    RoleScope.PORTAL);
            if (roleEntity != null) {
                roles.add(convert(roleEntity));
            }

            roleEntity = membershipService.getRole(
                    MembershipReferenceType.MANAGEMENT,
                    MembershipDefaultReferenceId.DEFAULT.name(),
                    user.getUsername(),
                    RoleScope.MANAGEMENT);
            if (roleEntity != null) {
                roles.add(convert(roleEntity));
            }

            userEntity.setRoles(roles);
        }

        return userEntity;
    }

    private UserRoleEntity convert(RoleEntity roleEntity) {
        if (roleEntity == null) {
            return null;
        }

        UserRoleEntity userRoleEntity = new UserRoleEntity();
        userRoleEntity.setScope(roleEntity.getScope());
        userRoleEntity.setName(roleEntity.getName());
        userRoleEntity.setPermissions(roleEntity.getPermissions());
        return userRoleEntity;
    }
}
