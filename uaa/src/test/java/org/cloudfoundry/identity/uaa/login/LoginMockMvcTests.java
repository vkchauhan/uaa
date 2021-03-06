/*******************************************************************************
 *     Cloud Foundry
 *     Copyright (c) [2009-2014] Pivotal Software, Inc. All Rights Reserved.
 *
 *     This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *     You may not use this product except in compliance with the License.
 *
 *     This product includes a number of subcomponents with
 *     separate copyright notices and license terms. Your use of these
 *     subcomponents is subject to the terms and conditions of the
 *     subcomponent's license, as noted in the LICENSE file.
 *******************************************************************************/
package org.cloudfoundry.identity.uaa.login;

import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.authentication.UaaPrincipal;
import org.cloudfoundry.identity.uaa.authentication.WhitelistLogoutHandler;
import org.cloudfoundry.identity.uaa.authentication.login.LoginInfoEndpoint;
import org.cloudfoundry.identity.uaa.authentication.login.Prompt;
import org.cloudfoundry.identity.uaa.client.ClientConstants;
import org.cloudfoundry.identity.uaa.codestore.JdbcExpiringCodeStore;
import org.cloudfoundry.identity.uaa.config.LockoutPolicy;
import org.cloudfoundry.identity.uaa.login.saml.IdentityProviderConfiguratorTests;
import org.cloudfoundry.identity.uaa.login.saml.SamlIdentityProviderDefinition;
import org.cloudfoundry.identity.uaa.mock.InjectedMockContextTest;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils;
import org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils.IdentityZoneCreationResult;
import org.cloudfoundry.identity.uaa.scim.ScimUser;
import org.cloudfoundry.identity.uaa.scim.ScimUserProvisioning;
import org.cloudfoundry.identity.uaa.scim.jdbc.JdbcScimUserProvisioning;
import org.cloudfoundry.identity.uaa.test.TestClient;
import org.cloudfoundry.identity.uaa.user.UaaAuthority;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.util.SetServerNameRequestPostProcessor;
import org.cloudfoundry.identity.uaa.web.CorsFilter;
import org.cloudfoundry.identity.uaa.zone.IdentityProvider;
import org.cloudfoundry.identity.uaa.zone.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.UaaIdentityProviderDefinition;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.crypto.codec.Base64;
import org.springframework.security.oauth2.common.util.RandomValueStringGenerator;
import org.springframework.security.oauth2.provider.client.BaseClientDetails;
import org.springframework.security.web.PortResolverImpl;
import org.springframework.security.web.authentication.logout.SimpleUrlLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.savedrequest.DefaultSavedRequest;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.context.support.XmlWebApplicationContext;

import javax.servlet.http.Cookie;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static java.util.Collections.EMPTY_LIST;
import static org.cloudfoundry.identity.uaa.mock.util.MockMvcUtils.CookieCsrfPostProcessor.cookieCsrf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertNotNull;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_HTML;
import static org.springframework.security.oauth2.common.util.OAuth2Utils.CLIENT_ID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.xpath;

public class LoginMockMvcTests extends InjectedMockContextTest {

    private static MockMvcUtils mockMvcUtils = MockMvcUtils.utils();

    private MockEnvironment mockEnvironment;

    private MockPropertySource propertySource;

    private Properties originalProperties = new Properties();

    Field f = ReflectionUtils.findField(MockEnvironment.class, "propertySource");

    private RandomValueStringGenerator generator = new RandomValueStringGenerator();

    private String adminToken;
    private XmlWebApplicationContext webApplicationContext;
    private Map<String,String> configuredDefaultLinks;

    @Before
    public void setUpContext() throws Exception {
        SecurityContextHolder.clearContext();
        webApplicationContext = getWebApplicationContext();
        mockEnvironment = (MockEnvironment) webApplicationContext.getEnvironment();
        f.setAccessible(true);
        propertySource = (MockPropertySource)ReflectionUtils.getField(f, mockEnvironment);
        for (String s : propertySource.getPropertyNames()) {
            originalProperties.put(s, propertySource.getProperty(s));
        }
        adminToken = MockMvcUtils.utils().getClientCredentialsOAuthAccessToken(getMockMvc(), "admin", "adminsecret", null, null);
        configuredDefaultLinks = new HashMap<>(webApplicationContext.getBean(LoginInfoEndpoint.class).getLinks());
    }

    @After
    public void tearDown() throws Exception {
        //restore all properties
        webApplicationContext.getBean(LoginInfoEndpoint.class).setLinks(configuredDefaultLinks);
        mockEnvironment.getPropertySources().remove(MockPropertySource.MOCK_PROPERTIES_PROPERTY_SOURCE_NAME);
        MockPropertySource originalPropertySource = new MockPropertySource(originalProperties);
        ReflectionUtils.setField(f, mockEnvironment, new MockPropertySource(originalProperties));
        mockEnvironment.getPropertySources().addLast(originalPropertySource);
        SecurityContextHolder.clearContext();
        IdentityZoneHolder.clear();
    }

    @Test
    public void testLogin() throws Exception {
        getMockMvc().perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attribute("links", hasEntry("forgotPasswordLink", "/forgot_password")))
            .andExpect(model().attribute("links", hasEntry("createAccountLink", "/create_account")))
            .andExpect(model().attributeExists("prompts"))
            .andExpect(content().string(containsString("/create_account")));
    }

    @Test
    public void testLogin_When_DisableInternalUserManagement_Is_True() throws Exception {
        webApplicationContext.getBean(LoginInfoEndpoint.class).setDisableInternalUserManagement(true);

        getMockMvc().perform(get("/login"))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attributeExists("prompts"))
            .andExpect(content().string(not(containsString("/create_account"))));

        webApplicationContext.getBean(LoginInfoEndpoint.class).setDisableInternalUserManagement(false);
    }

    @Test
    public void testCopyrightPivotal() throws Exception {
        mockEnvironment.setProperty("login.brand", "pivotal");

        getMockMvc().perform(get("/login"))
                .andExpect(content().string(containsString("Copyright &#169; Pivotal Software, Inc.")));
    }

    @Test
    public void testCopyrightCloudFoundry() throws Exception {
        mockEnvironment.setProperty("login.brand", "cloudfoundry");

        getMockMvc().perform(get("/login"))
                .andExpect(content().string(containsString("Copyright &#169; CloudFoundry.org Foundation, Inc.")));
    }

    @Test
    public void testForgotPasswordPageDoesNotHaveCsrf() throws Exception {
        getMockMvc().perform(get("/forgot_password"))
            .andExpect(status().isOk())
            .andExpect(view().name("forgot_password"))
            .andExpect(content().string(containsString("action=\"/forgot_password.do\"")))
            .andExpect(content().string(not(containsString("name=\"_csrf\""))));
    }

    @Test
    public void testForgotPasswordSubmitDoesNotValidateCsrf() throws Exception {
        getMockMvc().perform(
            post("/forgot_password.do")
                .param("email", "marissa@test.org")
                .with(csrf().useInvalidToken()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("email_sent?code=reset_password"));
    }

    @Test
    public void testChangePasswordPageDoesHaveCsrf() throws Exception {
        getMockMvc().perform(
            get("/change_password")
                .with(securityContext(MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext())))
        )
            .andExpect(status().isOk())
            .andExpect(view().name("change_password"))
            .andExpect(content().string(containsString("action=\"/change_password.do\"")))
            .andExpect(content().string(containsString("name=\"_csrf\"")));
    }

    @Test
    public void testChangePasswordSubmitDoesValidateCsrf() throws Exception {
        ScimUser user = createUser("", adminToken);
        getMockMvc().perform(
            post("/change_password.do")
                .with(securityContext(MockMvcUtils.utils().getUaaSecurityContext(user.getUserName(), getWebApplicationContext())))
                .param("current_password", user.getPassword())
                .param("new_password", "newSecr3t")
                .param("confirm_password", "newSecr3t")
                .with(csrf().useInvalidToken()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));

        getMockMvc().perform(
            post("/change_password.do")
                .with(securityContext(MockMvcUtils.utils().getUaaSecurityContext(user.getUserName(), getWebApplicationContext())))
                .param("current_password", user.getPassword())
                .param("new_password", "newSecr3t")
                .param("confirm_password", "newSecr3t")
                .with(csrf()))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("profile"));
    }

    private ScimUser createUser(String subdomain, String accessToken) throws Exception {
        String username = generator.generate()+"@testdomain.com";
        ScimUser user = new ScimUser(null, username, "Test", "User");
        user.setPrimaryEmail(username);
        user.setPassword("Secr3t");
        MockMvcUtils.utils().createUserInZone(getMockMvc(), accessToken, user, subdomain);
        return user;
    }

    @Test
    public void testLogOut() throws Exception {
        getMockMvc().perform(get("/logout.do"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    public void testLogOutIgnoreRedirectParameter() throws Exception {
        getMockMvc().perform(get("/logout.do").param("redirect", "https://www.google.com"))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    public void testLogOutEnableRedirectParameter() throws Exception {
        SimpleUrlLogoutSuccessHandler logoutSuccessHandler = getWebApplicationContext().getBean(SimpleUrlLogoutSuccessHandler.class);
        logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(false);
        try {
            getMockMvc().perform(get("/logout.do").param("redirect", "https://www.google.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://www.google.com"));
        } finally {
            logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(true);
        }
    }

    @Test
    public void testLogOutWhitelistedRedirectParameter() throws Exception {
        WhitelistLogoutHandler logoutSuccessHandler = getWebApplicationContext().getBean(WhitelistLogoutHandler.class);
        logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(false);
        logoutSuccessHandler.setWhitelist(Arrays.asList("https://www.google.com"));
        try {
            getMockMvc().perform(get("/logout.do").param("redirect", "https://www.google.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://www.google.com"));
        } finally {
            logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(true);
        }
    }

    @Test
    public void testLogOutNotWhitelistedRedirectParameter() throws Exception {
        WhitelistLogoutHandler logoutSuccessHandler = getWebApplicationContext().getBean(WhitelistLogoutHandler.class);
        logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(false);
        logoutSuccessHandler.setWhitelist(Arrays.asList("https://www.yahoo.com"));
        try {
            getMockMvc().perform(get("/logout.do").param("redirect", "https://www.google.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        } finally {
            logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(true);
        }
    }

    @Test
    public void testLogOutNullWhitelistedRedirectParameter() throws Exception {
        WhitelistLogoutHandler logoutSuccessHandler = getWebApplicationContext().getBean(WhitelistLogoutHandler.class);
        logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(false);
        logoutSuccessHandler.setWhitelist(null);
        try {
            getMockMvc().perform(get("/logout.do").param("redirect", "https://www.google.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://www.google.com"));
        } finally {
            logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(true);
        }
    }

    @Test
    public void testLogOutEmptyWhitelistedRedirectParameter() throws Exception {
        WhitelistLogoutHandler logoutSuccessHandler = getWebApplicationContext().getBean(WhitelistLogoutHandler.class);
        logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(false);
        logoutSuccessHandler.setWhitelist(EMPTY_LIST);
        try {
            getMockMvc().perform(get("/logout.do").param("redirect", "https://www.google.com"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        } finally {
            logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(true);
        }
    }

    @Test
    public void testLogOutChangeUrlValue() throws Exception {
        SimpleUrlLogoutSuccessHandler logoutSuccessHandler = getWebApplicationContext().getBean(SimpleUrlLogoutSuccessHandler.class);
        logoutSuccessHandler.setDefaultTargetUrl("https://www.google.com");
        try {
            getMockMvc().perform(get("/logout.do"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("https://www.google.com"));
        } finally {
            logoutSuccessHandler.setDefaultTargetUrl("/login");
        }
    }

    @Test
    public void testLogOutWithClientRedirect() throws Exception {
        WhitelistLogoutHandler logoutSuccessHandler = getWebApplicationContext().getBean(WhitelistLogoutHandler.class);
        logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(false);
        List<String> originalWhiteList = logoutSuccessHandler.getWhitelist();
        logoutSuccessHandler.setWhitelist(EMPTY_LIST);
        try {
            String clientId = generator.generate();
            String accessToken = mockMvcUtils.getClientOAuthAccessToken(getMockMvc(), "admin", "adminsecret", "");
            BaseClientDetails client = new BaseClientDetails(clientId, "", "", "client_credentials", "uaa.none", "http://*.wildcard.testing,http://testing.com");
            client.setClientSecret(clientId);
            MockMvcUtils.utils().createClient(getMockMvc(), accessToken, client);
            getMockMvc().perform(
                get("/logout.do")
                    .param(CLIENT_ID, clientId)
                    .param("redirect", "http://testing.com")
            )
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://testing.com"));

            getMockMvc().perform(
                get("/logout.do")
                    .param(CLIENT_ID, clientId)
                    .param("redirect", "http://www.wildcard.testing")
            )
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://www.wildcard.testing"));

            getMockMvc().perform(
                get("/logout.do")
                    .param(CLIENT_ID, "non-existent-client")
                    .param("redirect", "http://www.wildcard.testing")
            )
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/login"));
        } finally {
            String setAlwaysUseDefaultTargetUrl = getWebApplicationContext().getEnvironment().getProperty("logout.redirect.parameter.disable");
            boolean doUseTargetUrl = true;
            if ("false".equals(setAlwaysUseDefaultTargetUrl)) {
                doUseTargetUrl = false;
            }
            logoutSuccessHandler.setAlwaysUseDefaultTargetUrl(doUseTargetUrl);
            logoutSuccessHandler.setWhitelist(originalWhiteList);
        }
    }

    @Test
    public void testLoginWithAnalytics() throws Exception {
        mockEnvironment.setProperty("analytics.code", "secret_code");
        mockEnvironment.setProperty("analytics.domain", "example.com");

        getMockMvc().perform(get("/login").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(xpath("//body/script[contains(text(),'example.com')]").exists());
    }

    @Test
    public void testDefaultAndExternalizedBranding() throws Exception {
        getMockMvc().perform(MockMvcRequestBuilders.get("/login"))
            .andExpect(xpath("//head/link[@rel='shortcut icon']/@href").string("/resources/oss/images/favicon.ico"))
            .andExpect(xpath("//head/link[@href='/resources/oss/stylesheets/application.css']").exists())
            .andExpect(xpath("//div[@class='header' and contains(@style,'/resources/oss/images/logo.png')]").exists());

        mockEnvironment.setProperty("assetBaseUrl", "//cdn.example.com/pivotal");

        getMockMvc().perform(MockMvcRequestBuilders.get("/login"))
                .andExpect(xpath("//head/link[@rel='shortcut icon']/@href").string("//cdn.example.com/pivotal/images/favicon.ico"))
                .andExpect(xpath("//head/link[@href='//cdn.example.com/pivotal/stylesheets/application.css']").exists())
                .andExpect(xpath("//div[@class='header' and contains(@style,'//cdn.example.com/pivotal/images/logo.png')]").exists());
    }

    @Test
    public void testAccessConfirmationPage() throws Exception {
        ScimUserProvisioning userProvisioning = getWebApplicationContext().getBean(JdbcScimUserProvisioning.class);
        ScimUser marissa = userProvisioning.query("username eq \"marissa\" and origin eq \"uaa\"").get(0);
        UaaPrincipal uaaPrincipal = new UaaPrincipal(marissa.getId(), marissa.getUserName(), marissa.getPrimaryEmail(), marissa.getOrigin(), marissa.getExternalId(), IdentityZoneHolder.get().getId());

        UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(uaaPrincipal, null, Arrays.asList(UaaAuthority.fromAuthorities("uaa.user")));
        MockHttpSession session = new MockHttpSession();
        SecurityContext securityContext = new SecurityContextImpl();
        securityContext.setAuthentication(principal);
        session.putValue("SPRING_SECURITY_CONTEXT", securityContext);
        MockHttpServletRequestBuilder get = get("/oauth/authorize")
                .accept(TEXT_HTML)
                .param("response_type", "code")
                .param("client_id", "app")
                .param("state", "somestate")
                .param("redirect_uri", "http://localhost:8080/app/")
                .session(session)
            .principal(principal);
        getMockMvc().perform(get)
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/oauth/confirm_access"));
    }

    @Test
    public void testSignupsAndResetPasswordEnabled() throws Exception {
        webApplicationContext.getBean(LoginInfoEndpoint.class).setSelfServiceLinksEnabled(true);

        getMockMvc().perform(MockMvcRequestBuilders.get("/login"))
            .andExpect(xpath("//a[text()='Create account']").exists())
            .andExpect(xpath("//a[text()='Reset password']").exists());
    }

    @Test
    public void testSignupsAndResetPasswordDisabledWithNoLinksConfigured() throws Exception {
        webApplicationContext.getBean(LoginInfoEndpoint.class).setSelfServiceLinksEnabled(false);

        getMockMvc().perform(MockMvcRequestBuilders.get("/login"))
            .andExpect(xpath("//a[text()='Create account']").doesNotExist())
            .andExpect(xpath("//a[text()='Reset password']").doesNotExist());
    }

    @Test
    public void testSignupsAndResetPasswordDisabledWithSomeLinksConfigured() throws Exception {
        LoginInfoEndpoint endpoint = webApplicationContext.getBean(LoginInfoEndpoint.class);
        Map<String,String> links = endpoint.getLinks();
        links.put("signup", "http://example.com/signup");
        links.put("passwd", "http://example.com/reset_passwd");
        endpoint.setLinks(links);
        endpoint.setSelfServiceLinksEnabled(false);

        getMockMvc().perform(MockMvcRequestBuilders.get("/login"))
            .andExpect(xpath("//a[text()='Create account']").doesNotExist())
            .andExpect(xpath("//a[text()='Reset password']").doesNotExist());
    }

    @Test
    public void testSignupsAndResetPasswordEnabledWithCustomLinks() throws Exception {
        LoginInfoEndpoint endpoint = webApplicationContext.getBean(LoginInfoEndpoint.class);
        Map<String,String> links = endpoint.getLinks();
        links.put("signup", "http://example.com/signup");
        links.put("passwd", "http://example.com/reset_passwd");
        endpoint.setLinks(links);
        endpoint.setSelfServiceLinksEnabled(true);

        getMockMvc().perform(MockMvcRequestBuilders.get("/login"))
            .andExpect(xpath("//a[text()='Create account']/@href").string("http://example.com/signup"))
            .andExpect(xpath("//a[text()='Reset password']/@href").string("http://example.com/reset_passwd"));
    }

    @Test
    public void testLoginWithExplicitPrompts() throws Exception {
        LoginInfoEndpoint controller = webApplicationContext.getBean(LoginInfoEndpoint.class);
        List<Prompt> original = controller.getPrompts();
        try {
            Prompt first = new Prompt("how", "text", "How did I get here?");
            Prompt second = new Prompt("where", "password", "Where does that highway go to?");
            controller.setPrompts(Arrays.asList(first, second));

            getMockMvc().perform(get("/login").accept(TEXT_HTML))
                    .andExpect(status().isOk())
                    .andExpect(view().name("login"))
                    .andExpect(model().attribute("prompts", hasKey("how")))
                    .andExpect(model().attribute("prompts", hasKey("where")))
                    .andExpect(model().attribute("prompts", not(hasKey("password"))));
        } finally {
            controller.setPrompts(original);
        }
    }

    @Test
    public void testLoginWithExplicitJsonPrompts() throws Exception {
        LoginInfoEndpoint controller = webApplicationContext.getBean(LoginInfoEndpoint.class);
        List<Prompt> original = controller.getPrompts();
        try {
            Prompt first = new Prompt("how", "text", "How did I get here?");
            Prompt second = new Prompt("where", "password", "Where does that highway go to?");
            controller.setPrompts(Arrays.asList(first, second));

            getMockMvc().perform(get("/login")
                .accept(APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("prompts", hasKey("how")))
                .andExpect(model().attribute("prompts", hasKey("where")))
                .andExpect(model().attribute("prompts", not(hasKey("password"))));
        } finally {
            controller.setPrompts(original);
        }
    }


    @Test
    public void testLoginWithRemoteUaaPrompts() throws Exception {
        getMockMvc().perform(get("/login")
            .accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(view().name("login"))
                .andExpect(model().attribute("prompts", hasKey("username")))
                .andExpect(model().attribute("prompts", not(hasKey("passcode"))))
                .andExpect(model().attribute("prompts", hasKey("password")));
    }

    @Test
    public void testLoginWithRemoteUaaJsonPrompts() throws Exception {
        getMockMvc().perform(get("/login")
            .accept(APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attribute("prompts", hasKey("username")))
            .andExpect(model().attribute("prompts", hasKey("password")));
    }

    @Test
    public void testInfoWithRemoteUaaJsonPrompts() throws Exception {
        getMockMvc().perform(get("/info")
            .accept(APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attribute("prompts", hasKey("username")))
            .andExpect(model().attribute("prompts", hasKey("password")));
    }

    @Test
    public void testInfoWithRemoteUaaHtmlPrompts() throws Exception {
        getMockMvc().perform(get("/info")
            .accept(TEXT_HTML))
            .andExpect(status().isOk())
            .andExpect(view().name("login"))
            .andExpect(model().attribute("prompts", hasKey("username")))
            .andExpect(model().attribute("prompts", hasKey("password")));
    }


    @Test
    public void testDefaultAndCustomSignupLink() throws Exception {
        getMockMvc().perform(get("/login").accept(TEXT_HTML))
            .andExpect(status().isOk())
            .andExpect(model().attribute("links", hasEntry("createAccountLink", "/create_account")));
        LoginInfoEndpoint endpoint = webApplicationContext.getBean(LoginInfoEndpoint.class);
        Map<String,String> links = endpoint.getLinks();
        links.put("signup", "http://www.example.com/signup");
        endpoint.setLinks(links);
        getMockMvc().perform(get("/login").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(model().attribute("links", hasEntry("createAccountLink", "http://www.example.com/signup")));
    }

    @Test
    public void testLocalSignupDisabled() throws Exception {
        webApplicationContext.getBean(LoginInfoEndpoint.class).setSelfServiceLinksEnabled(false);
        getMockMvc().perform(get("/login").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(model().attribute("createAccountLink", nullValue()));
    }

    @Test
    public void testCustomSignupLinkWithLocalSignupDisabled() throws Exception {
        webApplicationContext.getBean(LoginInfoEndpoint.class).setSelfServiceLinksEnabled(false);
        LoginInfoEndpoint endpoint = webApplicationContext.getBean(LoginInfoEndpoint.class);
        Map<String,String> links = endpoint.getLinks();
        links.put("signup", "http://example.com/signup");
        endpoint.setLinks(links);
        getMockMvc().perform(get("/login").accept(TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(model().attribute("createAccountLink", nullValue()));
    }

    @Test
    public void testSamlLoginLinksShowActiveProviders() throws Exception {
        String activeAlias = "login-saml-"+generator.generate();
        String inactiveAlias = "login-saml-"+generator.generate();

        BaseClientDetails zoneAdminClient = new BaseClientDetails("admin", null, null, "client_credentials", "clients.admin,scim.read,scim.write");
        zoneAdminClient.setClientSecret("admin-secret");

        IdentityZoneCreationResult identityZoneCreationResult = mockMvcUtils.createOtherIdentityZoneAndReturnResult("puppy-" + new RandomValueStringGenerator().generate(), getMockMvc(), getWebApplicationContext(), zoneAdminClient);
        IdentityZone identityZone = identityZoneCreationResult.getIdentityZone();
        String zoneAdminToken = identityZoneCreationResult.getZoneAdminToken();

        String metadata = String.format(MockMvcUtils.IDP_META_DATA, new RandomValueStringGenerator().generate());
        SamlIdentityProviderDefinition activeSamlIdentityProviderDefinition = new SamlIdentityProviderDefinition(metadata, activeAlias, null, 0, false, true, "Active SAML Provider", null, identityZone.getId());
        IdentityProvider activeIdentityProvider = new IdentityProvider();
        activeIdentityProvider.setType(Origin.SAML);
        activeIdentityProvider.setName("Active SAML Provider");
        activeIdentityProvider.setConfig(activeSamlIdentityProviderDefinition);
        activeIdentityProvider.setActive(true);
        activeIdentityProvider.setOriginKey(activeAlias);
        mockMvcUtils.createIdpUsingWebRequest(getMockMvc(), identityZone.getId(), zoneAdminToken, activeIdentityProvider, status().isCreated());

        metadata = String.format(MockMvcUtils.IDP_META_DATA, new RandomValueStringGenerator().generate());
        SamlIdentityProviderDefinition inactiveSamlIdentityProviderDefinition = new SamlIdentityProviderDefinition(metadata, inactiveAlias, null, 0, false, true, "You should not see me", null, identityZone.getId());
        IdentityProvider inactiveIdentityProvider = new IdentityProvider();
        inactiveIdentityProvider.setType(Origin.SAML);
        inactiveIdentityProvider.setName("Inactive SAML Provider");
        inactiveIdentityProvider.setConfig(inactiveSamlIdentityProviderDefinition);
        inactiveIdentityProvider.setActive(false);
        inactiveIdentityProvider.setOriginKey(inactiveAlias);
        mockMvcUtils.createIdpUsingWebRequest(getMockMvc(), identityZone.getId(), zoneAdminToken, inactiveIdentityProvider, status().isCreated());

        getMockMvc().perform(get("/login").accept(TEXT_HTML).with(new SetServerNameRequestPostProcessor(identityZone.getSubdomain() + ".localhost")))
            .andExpect(status().isOk())
            .andExpect(xpath("//a[text()='" + activeSamlIdentityProviderDefinition.getLinkText() + "']").exists())
            .andExpect(xpath("//a[text()='" + inactiveSamlIdentityProviderDefinition.getLinkText() + "']").doesNotExist());
    }

    @Test
    public void testSamlRedirectWhenTheOnlyProvider() throws Exception {
        String alias = "login-saml-"+generator.generate();
        final String zoneAdminClientId = "admin";
        BaseClientDetails zoneAdminClient = new BaseClientDetails(zoneAdminClientId, null, "openid", "client_credentials,authorization_code", "clients.admin,scim.read,scim.write","http://test.redirect.com");
        zoneAdminClient.setClientSecret("admin-secret");

        IdentityZoneCreationResult identityZoneCreationResult = mockMvcUtils.createOtherIdentityZoneAndReturnResult("puppy-" + new RandomValueStringGenerator().generate(), getMockMvc(), getWebApplicationContext(), zoneAdminClient);
        IdentityZone identityZone = identityZoneCreationResult.getIdentityZone();
        String zoneAdminToken = identityZoneCreationResult.getZoneAdminToken();

        String metadata = String.format(MockMvcUtils.IDP_META_DATA, new RandomValueStringGenerator().generate());
        SamlIdentityProviderDefinition activeSamlIdentityProviderDefinition = new SamlIdentityProviderDefinition(metadata, alias, null, 0, false, true, "Active SAML Provider", null, identityZone.getId());
        IdentityProvider activeIdentityProvider = new IdentityProvider();
        activeIdentityProvider.setType(Origin.SAML);
        activeIdentityProvider.setName("Active SAML Provider");
        activeIdentityProvider.setActive(true);
        activeIdentityProvider.setConfig(activeSamlIdentityProviderDefinition);
        activeIdentityProvider.setOriginKey(alias);
        activeIdentityProvider = mockMvcUtils.createIdpUsingWebRequest(getMockMvc(), identityZone.getId(), zoneAdminToken, activeIdentityProvider, status().isCreated());

        zoneAdminClient.addAdditionalInformation(ClientConstants.ALLOWED_PROVIDERS, Collections.singletonList(activeIdentityProvider.getOriginKey()));
        mockMvcUtils.updateClient(getMockMvc(), zoneAdminToken, zoneAdminClient, identityZone);

        MockHttpSession session = new MockHttpSession();
        SavedRequest savedRequest = new DefaultSavedRequest(new MockHttpServletRequest(), new PortResolverImpl()) {
            @Override
            public String getRedirectUrl() {
                return "http://test/redirect/oauth/authorize";
            }
            @Override
            public String[] getParameterValues(String name) {
                if ("client_id".equals(name)) {
                    return new String[] {"admin"};
                }
                return new String[0];
            }
            @Override public List<Cookie> getCookies() { return null; }
            @Override public String getMethod() { return null; }
            @Override public List<String> getHeaderValues(String name) { return null; }
            @Override
            public Collection<String> getHeaderNames() { return null; }
            @Override public List<Locale> getLocales() { return null; }
            @Override public Map<String, String[]> getParameterMap() { return null; }
        };
        session.setAttribute("SPRING_SECURITY_SAVED_REQUEST", savedRequest);

        getMockMvc().perform(get("/login").accept(TEXT_HTML).with(new SetServerNameRequestPostProcessor(identityZone.getSubdomain() + ".localhost"))
            .session(session)
            .with(new SetServerNameRequestPostProcessor(identityZone.getSubdomain() + ".localhost")))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("saml/discovery?returnIDParam=idp&entityID=" + identityZone.getSubdomain() + ".cloudfoundry-saml-login&idp="+alias+"&isPassive=true"));
    }

    @Test
    public void testNoCreateAccountLinksWhenUAAisNotAllowedProvider() throws Exception {
        String alias2 = "login-saml-"+generator.generate();
        String alias3 = "login-saml-"+generator.generate();
        final String zoneAdminClientId = "admin";
        BaseClientDetails zoneAdminClient = new BaseClientDetails(zoneAdminClientId, null, "openid", "client_credentials,authorization_code", "clients.admin,scim.read,scim.write","http://test.redirect.com");
        zoneAdminClient.setClientSecret("admin-secret");

        IdentityZoneCreationResult identityZoneCreationResult = mockMvcUtils.createOtherIdentityZoneAndReturnResult("puppy-" + new RandomValueStringGenerator().generate(), getMockMvc(), getWebApplicationContext(), zoneAdminClient);
        IdentityZone identityZone = identityZoneCreationResult.getIdentityZone();
        String zoneAdminToken = identityZoneCreationResult.getZoneAdminToken();

        SamlIdentityProviderDefinition activeSamlIdentityProviderDefinition3 = new SamlIdentityProviderDefinition(
            String.format(IdentityProviderConfiguratorTests.xmlWithoutID,"http://example3.com/saml/metadata"),
            alias3,
            null,
            0,
            false,
            true,
            "Active3 SAML Provider",
            null,
            identityZone.getId()
        );
        IdentityProvider activeIdentityProvider3 = new IdentityProvider();
        activeIdentityProvider3.setType(Origin.SAML);
        activeIdentityProvider3.setName("Active 3 SAML Provider");
        activeIdentityProvider3.setActive(true);
        activeIdentityProvider3.setConfig(activeSamlIdentityProviderDefinition3);
        activeIdentityProvider3.setOriginKey(alias3);
        activeIdentityProvider3 = mockMvcUtils.createIdpUsingWebRequest(getMockMvc(), identityZone.getId(), zoneAdminToken, activeIdentityProvider3, status().isCreated());

        SamlIdentityProviderDefinition activeSamlIdentityProviderDefinition2 = new SamlIdentityProviderDefinition(String.format(IdentityProviderConfiguratorTests.xmlWithoutID,"http://example2.com/saml/metadata"), alias2, null, 0, false, true, "Active2 SAML Provider", null, identityZone.getId());
        IdentityProvider activeIdentityProvider2 = new IdentityProvider();
        activeIdentityProvider2.setType(Origin.SAML);
        activeIdentityProvider2.setName("Active 2 SAML Provider");
        activeIdentityProvider2.setActive(true);
        activeIdentityProvider2.setConfig(activeSamlIdentityProviderDefinition2);
        activeIdentityProvider2.setOriginKey(alias2);
        activeIdentityProvider2 = mockMvcUtils.createIdpUsingWebRequest(getMockMvc(), identityZone.getId(), zoneAdminToken, activeIdentityProvider2, status().isCreated());

        zoneAdminClient.addAdditionalInformation(ClientConstants.ALLOWED_PROVIDERS, Arrays.asList(activeIdentityProvider3.getOriginKey(), activeIdentityProvider2.getOriginKey()));
        mockMvcUtils.updateClient(getMockMvc(), zoneAdminToken, zoneAdminClient, identityZone);

        MockHttpSession session = new MockHttpSession();
        SavedRequest savedRequest = new DefaultSavedRequest(new MockHttpServletRequest(), new PortResolverImpl()) {
            @Override
            public String getRedirectUrl() {
                return "http://test/redirect/oauth/authorize";
            }
            @Override
            public String[] getParameterValues(String name) {
                if ("client_id".equals(name)) {
                    return new String[] {"admin"};
                }
                return new String[0];
            }
            @Override public List<Cookie> getCookies() { return null; }
            @Override public String getMethod() { return null; }
            @Override public List<String> getHeaderValues(String name) { return null; }
            @Override
            public Collection<String> getHeaderNames() { return null; }
            @Override public List<Locale> getLocales() { return null; }
            @Override public Map<String, String[]> getParameterMap() { return null; }
        };
        session.setAttribute("SPRING_SECURITY_SAVED_REQUEST", savedRequest);

        getMockMvc().perform(get("/login").accept(TEXT_HTML).with(new SetServerNameRequestPostProcessor(identityZone.getSubdomain() + ".localhost"))
            .session(session)
            .with(new SetServerNameRequestPostProcessor(identityZone.getSubdomain() + ".localhost")))
            .andExpect(status().isOk())
            .andExpect(xpath("//a[text()='Create account']").doesNotExist())
            .andExpect(xpath("//a[text()='Reset password']").doesNotExist());


    }

    @Test
    public void testDeactivatedProviderIsRemovedFromSamlLoginLinks() throws Exception {
        String alias = "login-saml-"+generator.generate();
        BaseClientDetails zoneAdminClient = new BaseClientDetails("admin", null, null, "client_credentials", "clients.admin,scim.read,scim.write");
        zoneAdminClient.setClientSecret("admin-secret");

        IdentityZoneCreationResult identityZoneCreationResult = mockMvcUtils.createOtherIdentityZoneAndReturnResult("puppy-" + new RandomValueStringGenerator().generate(), getMockMvc(), getWebApplicationContext(), zoneAdminClient);
        IdentityZone identityZone = identityZoneCreationResult.getIdentityZone();
        String zoneAdminToken = identityZoneCreationResult.getZoneAdminToken();

        String metadata = String.format(MockMvcUtils.IDP_META_DATA, new RandomValueStringGenerator().generate());
        SamlIdentityProviderDefinition samlIdentityProviderDefinition = new SamlIdentityProviderDefinition(metadata, alias, null, 0, false, true, "SAML Provider", null, identityZone.getId());
        IdentityProvider identityProvider = new IdentityProvider();
        identityProvider.setType(Origin.SAML);
        identityProvider.setName("SAML Provider");
        identityProvider.setActive(true);
        identityProvider.setConfig(samlIdentityProviderDefinition);
        identityProvider.setOriginKey(alias);

        identityProvider = mockMvcUtils.createIdpUsingWebRequest(getMockMvc(), identityZone.getId(), zoneAdminToken, identityProvider, status().isCreated());

        getMockMvc().perform(get("/login").accept(TEXT_HTML).with(new SetServerNameRequestPostProcessor(identityZone.getSubdomain() + ".localhost")))
            .andExpect(status().isOk())
            .andExpect(xpath("//a[text()='" + samlIdentityProviderDefinition.getLinkText() + "']").exists());

        identityProvider.setActive(false);
        mockMvcUtils.createIdpUsingWebRequest(getMockMvc(), identityZone.getId(), zoneAdminToken, identityProvider, status().isOk(), true);

        getMockMvc().perform(get("/login").accept(TEXT_HTML).with(new SetServerNameRequestPostProcessor(identityZone.getSubdomain() + ".localhost")))
            .andExpect(status().isOk())
            .andExpect(xpath("//a[text()='" + samlIdentityProviderDefinition.getLinkText() + "']").doesNotExist());
    }

    @Test
    public void testChangeEmailWithoutAuthenticationReturnsRedirect() throws Exception {
        getMockMvc().perform(get("/change_email").accept(TEXT_HTML))
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    public void testChangeEmailPageHasCsrf() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());

        MockHttpServletRequestBuilder get = get("/change_email")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext));
        getMockMvc().perform(get)
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("_csrf")));
    }

    @Test
    public void testChangeEmailSubmitWithMissingCsrf() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());

        MockHttpServletRequestBuilder get = get("/change_email")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext));
        MockHttpSession session = (MockHttpSession) getMockMvc().perform(get)
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("_csrf")))
            .andReturn().getRequest().getSession();
        assertNotNull(session.getAttribute(HttpSessionCsrfTokenRepository.class.getName().concat(".CSRF_TOKEN")));

        MockHttpServletRequestBuilder changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .session(session)
            .with(securityContext(marissaContext))
            .param("newEmail", "test@test.org")
            .param("client_id", "");
        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));
    }

    @Test
    public void testChangeEmailSubmitWithInvalidCsrf() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());

        MockHttpServletRequestBuilder get = get("/change_email")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext));
        MockHttpSession session = (MockHttpSession) getMockMvc().perform(get)
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("_csrf")))
            .andReturn().getRequest().getSession();
        assertNotNull(session.getAttribute(HttpSessionCsrfTokenRepository.class.getName().concat(".CSRF_TOKEN")));

        MockHttpServletRequestBuilder changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .session(session)
            .with(securityContext(marissaContext))
            .param("newEmail", "test@test.org")
            .param("client_id", "")
            .param("_csrf", "invalid csrf token");
        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));
    }

    @Test
    public void testChangeEmailSubmitWithSpringSecurityForcedCsrf() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());
        //example shows to to test a request that is secured by csrf and you wish to bypass it
        MockHttpServletRequestBuilder changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext))
            .with(csrf())
            .param("newEmail", "test@test.org")
            .param("client_id", "");

        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("email_sent?code=email_change"));
    }

    @Test
    public void testChangeEmailSubmitWithCorrectCsrf() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());

        MockHttpServletRequestBuilder get = get("/change_email")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext));

        MvcResult result = getMockMvc().perform(get)
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("_csrf")))
            .andReturn();

        MockHttpSession session = (MockHttpSession)result.getRequest().getSession();
        CsrfToken csrfToken = (CsrfToken)session.getAttribute(HttpSessionCsrfTokenRepository.class.getName().concat(".CSRF_TOKEN"));

        MockHttpServletRequestBuilder changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext))
            .session(session)
            .param("newEmail", "test@test.org")
            .param("client_id", "")
            .param("_csrf", csrfToken.getToken());
        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("email_sent?code=email_change"));

    }

    @Test
    public void testChangeEmailDoNotLoggedIn() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());

        MockHttpServletRequestBuilder changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .with(csrf());
        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/login"));

        changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .with(csrf().useInvalidToken());
        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));

        changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .with(csrf().useInvalidToken())
            .with(securityContext(marissaContext));
        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));
    }

    @Test
    public void testChangeEmailNoCsrfReturns403AndInvalidRequest() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());

        MockHttpServletRequestBuilder get = get("/change_email")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext));

        getMockMvc().perform(get)
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("_csrf")))
            .andReturn();

        MockHttpServletRequestBuilder changeEmail = post("/change_email.do")
            .accept(TEXT_HTML)
            .with(securityContext(marissaContext))
            .param("newEmail", "test@test.org")
            .param("client_id", "");
        getMockMvc().perform(changeEmail)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));
    }


    @Test
    public void testCsrfForInvitationAcceptPost() throws Exception {
        SecurityContext marissaContext = MockMvcUtils.utils().getMarissaSecurityContext(getWebApplicationContext());
        AnonymousAuthenticationToken inviteToken = new AnonymousAuthenticationToken("invited-test", marissaContext.getAuthentication().getPrincipal(), Arrays.asList(UaaAuthority.UAA_INVITED));
        MockHttpSession inviteSession = new MockHttpSession();
        SecurityContext inviteContext = new SecurityContextImpl();
        inviteContext.setAuthentication(inviteToken);
        inviteSession.setAttribute("SPRING_SECURITY_CONTEXT", inviteContext);

        //logged in with valid CSRF
        MockHttpServletRequestBuilder post = post("/invitations/accept.do")
            .session(inviteSession)
            .with(csrf())
            .param("code","thecode")
            .param("client_id", "random")
            .param("password", "password")
            .param("password_confirmation", "yield_unprocessable_entity");

        getMockMvc().perform(post)
            .andExpect(status().isUnprocessableEntity());

        //logged in, invalid CSRF
        post = post("/invitations/accept.do")
            .session(inviteSession)
            .with(csrf().useInvalidToken())
            .param("client_id", "random")
            .param("password", "password")
            .param("password_confirmation", "yield_unprocessable_entity");

        getMockMvc().perform(post)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));

        //not logged in, no CSRF
        post = post("/invitations/accept.do")
            .param("client_id", "random")
            .param("password", "password")
            .param("password_confirmation", "yield_unprocessable_entity");

        getMockMvc().perform(post)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/invalid_request"));


        //not logged in, valid CSRF(can't happen)
        post = post("/invitations/accept.do")
            .with(csrf())
            .param("client_id", "random")
            .param("password", "password")
            .param("code", "notvalidated")
            .param("password_confirmation", "yield_unprocessable_entity");

        getMockMvc().perform(post)
            .andExpect(status().isFound())
            .andExpect(redirectedUrl("http://localhost/login"));

    }

    /**
     * Positive test case that exercises the CORS logic for dealing with the "X-Requested-With" header.
     *
     * @throws Exception
     */
    @Test
    public void testLogOutCorsPreflight() throws Exception {
        CorsFilter corsFilter = getWebApplicationContext().getBean(CorsFilter.class);
        corsFilter.setCorsXhrAllowedOrigins(Arrays.asList(new String[] {"^localhost$", "^*\\.localhost$"}));
        corsFilter.setCorsXhrAllowedUris(Arrays.asList(new String[] {"^/logout\\.do$"}));
        corsFilter.initialize();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Access-Control-Request-Headers", "X-Requested-With");
        httpHeaders.add("Access-Control-Request-Method", "GET");
        httpHeaders.add("Origin", "localhost");
        getMockMvc().perform(options("/logout.do").headers(httpHeaders)).andExpect(status().isOk());
    }

    /**
     * Positive test case that exercises the CORS logic for dealing with the "X-Requested-With" header.
     *
     * @throws Exception
     */
    @Test
    public void testLogOutCorsPreflightForIdentityZone() throws Exception {
        CorsFilter corsFilter = getWebApplicationContext().getBean(CorsFilter.class);
        corsFilter.setCorsXhrAllowedOrigins(Arrays.asList(new String[] {"^localhost$", "^*\\.localhost$"}));
        corsFilter.setCorsXhrAllowedUris(Arrays.asList(new String[] {"^/logout.do$"}));
        corsFilter.initialize();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Access-Control-Request-Headers", "X-Requested-With");
        httpHeaders.add("Access-Control-Request-Method", "GET");
        httpHeaders.add("Origin", "testzone1.localhost");
        getMockMvc().perform(options("/logout.do").headers(httpHeaders)).andExpect(status().isOk());
    }

    /**
     * This should avoid the logic for X-Requested-With header entirely.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testLogOutCorsPreflightWithStandardHeader() throws Exception {
        CorsFilter corsFilter = getWebApplicationContext().getBean(CorsFilter.class);
        corsFilter.setCorsXhrAllowedOrigins(Arrays.asList(new String[] {"^localhost$"}));
        corsFilter.setCorsXhrAllowedUris(Arrays.asList(new String[] {"^/logout\\.do$"}));
        corsFilter.initialize();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Access-Control-Request-Headers", "Accept");
        httpHeaders.add("Access-Control-Request-Method", "GET");
        httpHeaders.add("Origin", "localhost");
        getMockMvc().perform(options("/logout.do").headers(httpHeaders)).andExpect(status().isOk());
    }

    /**
     * The endpoint is not white-listed to allow CORS requests with the "X-Requested-With" header so the
     * CorsFilter returns a 403.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testLogOutCorsPreflightWithUnallowedEndpoint() throws Exception {
        CorsFilter corsFilter = getWebApplicationContext().getBean(CorsFilter.class);
        corsFilter.setCorsXhrAllowedOrigins(Arrays.asList(new String[] {"^localhost$"}));
        corsFilter.setCorsXhrAllowedUris(Arrays.asList(new String[] {"^/logout\\.do$"}));
        corsFilter.initialize();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Access-Control-Request-Headers", "X-Requested-With");
        httpHeaders.add("Access-Control-Request-Method", "GET");
        httpHeaders.add("Origin", "localhost");
        getMockMvc().perform(options("/logout.dont").headers(httpHeaders)).andExpect(status().isForbidden());
    }

    /**
     * The access control request method is not a GET therefore CORS requests with the "X-Requested-With"
     * header are not allowed and the CorsFilter returns a 405.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testLogOutCorsPreflightWithUnallowedMethod() throws Exception {
        CorsFilter corsFilter = getWebApplicationContext().getBean(CorsFilter.class);
        corsFilter.setCorsXhrAllowedOrigins(Arrays.asList(new String[] {"^localhost$"}));
        corsFilter.setCorsXhrAllowedUris(Arrays.asList(new String[] {"^/logout\\.do$"}));
        corsFilter.initialize();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Access-Control-Request-Headers", "X-Requested-With");
        httpHeaders.add("Access-Control-Request-Method", "POST");
        httpHeaders.add("Origin", "localhost");
        getMockMvc().perform(options("/logout.do").headers(httpHeaders)).andExpect(status().isMethodNotAllowed());
    }

    /**
     * The request origin is not white-listed to allow CORS requests with the "X-Requested-With" header so the
     * CorsFilter returns a 403.
     *
     * @throws Exception on test failure
     */
    @Test
    public void testLogOutCorsPreflightWithUnallowedOrigin() throws Exception {
        CorsFilter corsFilter = getWebApplicationContext().getBean(CorsFilter.class);
        corsFilter.setCorsXhrAllowedOrigins(Arrays.asList(new String[] {"^localhost$"}));
        corsFilter.setCorsXhrAllowedUris(Arrays.asList(new String[] {"^/logout\\.do$"}));
        corsFilter.initialize();

        HttpHeaders httpHeaders = new HttpHeaders();
        httpHeaders.add("Access-Control-Request-Headers", "X-Requested-With");
        httpHeaders.add("Access-Control-Request-Method", "GET");
        httpHeaders.add("Origin", "fuzzybunnies.com");
        getMockMvc().perform(options("/logout.do").headers(httpHeaders)).andExpect(status().isForbidden());
    }

    @Test
    public void login_LockoutPolicySucceeds_ForDefaultZone() throws Exception {
        ScimUser userToLockout = createUser("", adminToken);
        attemptFailedLogin(5, userToLockout.getUserName(), "");
        getMockMvc().perform(post("/login.do")
            .with(cookieCsrf())
            .param("username", userToLockout.getUserName())
            .param("password", userToLockout.getPassword()))
            .andExpect(redirectedUrl("/login?error=account_locked"));
    }

    @Test
    public void login_LockoutPolicySucceeds_WhenPolicyIsUpdatedByApi() throws Exception {
        String subdomain = generator.generate();
        IdentityZone zone = mockMvcUtils.createOtherIdentityZone(subdomain, getMockMvc(), getWebApplicationContext());

        changeLockoutPolicyForIdpInZone(zone);

        TestClient testClient = new TestClient(getMockMvc());
        String zoneAdminToken = testClient.getClientCredentialsOAuthAccessToken("admin", "admin-secret", "scim.write,idps.write", zone.getSubdomain());

        ScimUser userToLockout = createUser(subdomain, zoneAdminToken);

        attemptFailedLogin(2, userToLockout.getUserName(), subdomain);

        getMockMvc().perform(post("/login.do")
            .with(new SetServerNameRequestPostProcessor(subdomain + ".localhost"))
            .with(cookieCsrf())
            .param("username", userToLockout.getUserName())
            .param("password", userToLockout.getPassword()))
            .andExpect(redirectedUrl("/login?error=account_locked"));
    }

    @Test
    public void autologin_with_validCode_RedirectsToSavedRequest_ifPresent() throws Exception {
        MockHttpSession session = MockMvcUtils.utils().getSavedRequestSession();

        MockMvcUtils.PredictableGenerator generator = new MockMvcUtils.PredictableGenerator();
        JdbcExpiringCodeStore store = getWebApplicationContext().getBean(JdbcExpiringCodeStore.class);
        store.setGenerator(generator);

        AutologinRequest request = new AutologinRequest();
        request.setUsername("marissa");
        request.setPassword("koala");
        getMockMvc().perform(post("/autologin")
                .header("Authorization", "Basic " + new String(new Base64().encode("admin:adminsecret".getBytes())))
                .contentType(APPLICATION_JSON)
                .content(JsonUtils.writeValueAsString(request)))
                .andExpect(status().isOk());

        getMockMvc().perform(get("/autologin")
            .session(session)
            .param("code", "test" + generator.counter.get())
            .param("client_id", "admin"))
            .andExpect(redirectedUrl("http://test/redirect/oauth/authorize"));
    }

    @Test
    public void autologin_with_validCode_RedirectsToHome() throws Exception {
        MockMvcUtils.PredictableGenerator generator = new MockMvcUtils.PredictableGenerator();
        JdbcExpiringCodeStore store = getWebApplicationContext().getBean(JdbcExpiringCodeStore.class);
        store.setGenerator(generator);

        AutologinRequest request = new AutologinRequest();
        request.setUsername("marissa");
        request.setPassword("koala");
        getMockMvc().perform(post("/autologin")
                .header("Authorization", "Basic " + new String(new Base64().encode("admin:adminsecret".getBytes())))
                .contentType(APPLICATION_JSON)
                .content(JsonUtils.writeValueAsString(request)))
                .andExpect(status().isOk());

        getMockMvc().perform(get("/autologin")
                .param("code", "test" + generator.counter.get())
                .param("client_id", "admin"))
                .andExpect(redirectedUrl("home"));
    }

    private void changeLockoutPolicyForIdpInZone(IdentityZone zone) throws Exception {
        IdentityProviderProvisioning identityProviderProvisioning = getWebApplicationContext().getBean(IdentityProviderProvisioning.class);
        IdentityProvider identityProvider = identityProviderProvisioning.retrieveByOrigin(Origin.UAA, zone.getId());

        LockoutPolicy policy = new LockoutPolicy();
        policy.setLockoutAfterFailures(2);
        policy.setLockoutPeriodSeconds(3600);
        policy.setCountFailuresWithin(900);

        UaaIdentityProviderDefinition configMap = new UaaIdentityProviderDefinition(null, policy);

        identityProvider.setConfig(configMap);

        TestClient testClient = new TestClient(getMockMvc());
        String zoneAdminToken = testClient.getClientCredentialsOAuthAccessToken("admin", "admin-secret", "scim.write,idps.write", zone.getSubdomain());

        getMockMvc().perform(put("/identity-providers/" + identityProvider.getId())
                .with(new SetServerNameRequestPostProcessor(zone.getSubdomain() + ".localhost"))
                .content(JsonUtils.writeValueAsString(identityProvider))
                .contentType(APPLICATION_JSON)
                .header("Authorization", "bearer " + zoneAdminToken)).andExpect(status().isOk());
    }

    private void attemptFailedLogin(int numberOfAttempts, String username, String subdomain) throws Exception {
        String requestDomain = subdomain.equals("") ? "localhost" : subdomain + ".localhost";
        MockHttpServletRequestBuilder post = post("/login.do")
            .with(new SetServerNameRequestPostProcessor(requestDomain))
            .with(cookieCsrf())
            .param("username", username)
            .param("password", "wrong_password");
        for (int i = 0; i < numberOfAttempts ; i++) {
            getMockMvc().perform(post)
                .andExpect(redirectedUrl("/login?error=login_failure"));
        }
    }
}
