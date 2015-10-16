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
package io.gravitee.management.rest;

import java.io.IOException;
import java.security.Principal;

import javax.ws.rs.client.WebTarget;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.SecurityContext;

import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.test.JerseyTest;
import org.junit.After;
import org.junit.Before;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import io.gravitee.management.rest.resource.GraviteeApplication;

/**
 * @author David BRASSELY (brasseld at gmail.com)
 */
public abstract class JerseySpringTest {

    protected static final String USER_NAME = "UnitTests";

    private JerseyTest _jerseyTest;

    protected abstract String contextPath();

    public final WebTarget target()
    {
        return target("");
    }

    public final WebTarget target(final String path)
    {
        return _jerseyTest.target(contextPath() + path);
    }

    @Before
    public void setup() throws Exception
    {
        _jerseyTest.setUp();
    }

    @After
    public void tearDown() throws Exception
    {
        _jerseyTest.tearDown();
    }

    @Autowired
    public void setApplicationContext(final ApplicationContext context)
    {
        _jerseyTest = new JerseyTest()
        {
            @Override
            protected Application configure()
            {
                ResourceConfig application = new GraviteeApplication();

                application.property("contextConfig", context);
                application.register(AuthenticationFilter.class);


                return application;
            }
        };
    }

     public static class AuthenticationFilter implements ContainerRequestFilter {
        @Override
        public void filter(final ContainerRequestContext requestContext) throws IOException {
            requestContext.setSecurityContext(new SecurityContext() {
                @Override
                public Principal getUserPrincipal() {
                    return () -> USER_NAME;
                }
                @Override
                public boolean isUserInRole(String string) {
                    return true;
                }
                @Override
                public boolean isSecure() { return true; }
                @Override
                public String getAuthenticationScheme() { return "BASIC"; }
            });
        }
    }

//    protected abstract ResourceConfig configure();
}
