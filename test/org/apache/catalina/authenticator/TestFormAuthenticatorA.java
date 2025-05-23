/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.catalina.authenticator;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.junit.Assert;
import org.junit.Test;

import org.apache.catalina.Context;
import org.apache.catalina.Valve;
import org.apache.catalina.startup.SimpleHttpClient;
import org.apache.catalina.startup.TesterMapRealm;
import org.apache.catalina.startup.TesterServlet;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.startup.TomcatBaseTest;
import org.apache.tomcat.util.buf.ByteChunk;
import org.apache.tomcat.util.descriptor.web.LoginConfig;
import org.apache.tomcat.util.descriptor.web.SecurityCollection;
import org.apache.tomcat.util.descriptor.web.SecurityConstraint;
import org.apache.tomcat.websocket.server.WsContextListener;

/*
 * Test FORM authentication for sessions that do and do not use cookies.
 *
 * 1. A client that can accept and respond to a Set-Cookie for JSESSIONID
 *    will be able to maintain its authenticated session, no matter whether
 *    the session ID is changed once, many times, or not at all.
 *
 * 2. A client that cannot accept cookies will only be able to maintain a
 *    persistent session IF the server sends the correct (current) jsessionid
 *    as a path parameter appended to ALL urls within its response. That is
 *    achievable with servlets, jsps, jstl (all of which which can ask for an
 *    encoded url to be inserted into the dynamic web page). It cannot work
 *    with static HTML.
 *    note: this test class uses the Tomcat sample jsps, which conform.
 *
 * 3. Therefore, any webapp that MIGHT need to authenticate a client that
 *    does not accept cookies MUST generate EVERY protected resource url
 *    dynamically (so that it will include the current session ID).
 *
 * 4. Any webapp that cannot satisfy case 3 MUST turn off
 *    changeSessionIdOnAuthentication for its Context and thus degrade the
 *    session fixation protection for ALL of its clients.
 *    note from MarkT: Not sure I agree with this. If the URLs aren't
 *      being encoded, then the session is going to break regardless of
 *      whether or not the session ID changes.
 *
 * Unlike a "proper browser", this unit test class does a quite lot of
 * screen-scraping and cheating of headers and urls (not very elegant,
 * but it makes no claims to generality).
 *
 */
public class TestFormAuthenticatorA extends TomcatBaseTest {

    // these should really be singletons to be type-safe,
    // we are in a unit test and don't need to paranoid.
    protected static final boolean USE_100_CONTINUE = true;
    protected static final boolean NO_100_CONTINUE = !USE_100_CONTINUE;

    protected static final boolean CLIENT_USE_COOKIES = true;
    protected static final boolean CLIENT_NO_COOKIES = !CLIENT_USE_COOKIES;

    protected static final boolean CLIENT_USE_HTTP_11 = true;
    protected static final boolean CLIENT_USE_HTTP_10 = !CLIENT_USE_HTTP_11;

    protected static final boolean SERVER_USE_COOKIES = true;
    protected static final boolean SERVER_NO_COOKIES = !SERVER_USE_COOKIES;

    protected static final boolean SERVER_CHANGE_SESSID = true;
    protected static final boolean SERVER_FREEZE_SESSID = !SERVER_CHANGE_SESSID;

    // minimum session timeout
    private static final int SHORT_SESSION_TIMEOUT_SECS = 1;
    private static final long TIMEOUT_DELAY_MSECS = ((SHORT_SESSION_TIMEOUT_SECS + 10) * 1000);

    private FormAuthClient client;

    // first, a set of tests where the server uses a cookie to carry
    // the current session ID during and after authentication, and
    // the client is prepared to return cookies with each request

    @Test
    public void testGetWithCookies() throws Exception {
        doTest("GET", "GET", NO_100_CONTINUE,
                CLIENT_USE_COOKIES, SERVER_USE_COOKIES, SERVER_CHANGE_SESSID);
    }




    // next, a set of tests where the server Context is configured to never
    // use cookies and the session ID is only carried as a url path parameter

    // Bug 53584
    @Test
    public void testGetNoServerCookies() throws Exception {
        doTest("GET", "GET", NO_100_CONTINUE,
                CLIENT_NO_COOKIES, SERVER_NO_COOKIES, SERVER_CHANGE_SESSID);
    }




    // next, a set of tests where the server Context uses cookies,
    // but the client refuses to return them and tries to use
    // the session ID if carried as a url path parameter

    @Test
    public void testGetNoClientCookies() throws Exception {
        doTest("GET", "GET", NO_100_CONTINUE,
                CLIENT_NO_COOKIES, SERVER_USE_COOKIES, SERVER_CHANGE_SESSID);
    }


    // finally, a set of tests to explore quirky situations
    // but there is not need to replicate all the scenarios above.

    @Test
    public void testNoChangedSessidWithCookies() throws Exception {
        doTest("GET", "GET", NO_100_CONTINUE,
                CLIENT_USE_COOKIES, SERVER_USE_COOKIES,
                SERVER_FREEZE_SESSID);
    }

    @Test
    public void testNoChangedSessidWithoutCookies() throws Exception {
        doTest("GET", "GET", NO_100_CONTINUE,
                CLIENT_NO_COOKIES, SERVER_USE_COOKIES,
                SERVER_FREEZE_SESSID);
    }

    @Test
    public void testTimeoutWithoutCookies() throws Exception {
        String protectedUri = doTest("GET", "GET", NO_100_CONTINUE,
                CLIENT_NO_COOKIES, SERVER_USE_COOKIES,
                SERVER_FREEZE_SESSID);

        // Force session to expire one second from now
        Context context = (Context) getTomcatInstance().getHost().findChildren()[0];
        forceSessionMaxInactiveInterval(context, SHORT_SESSION_TIMEOUT_SECS);

        // wait long enough for my session to expire
        Thread.sleep(TIMEOUT_DELAY_MSECS);

        // then try to continue using the expired session to get the
        // protected resource once more.
        // should get login challenge or timeout status 408
        doTestProtected("GET", protectedUri, NO_100_CONTINUE,
                FormAuthClient.LOGIN_REQUIRED, 1);
    }

    // HTTP 1.0 test
    @Test
    public void testGetWithCookiesHttp10() throws Exception {
        doTest("GET", "GET", NO_100_CONTINUE,
                CLIENT_USE_COOKIES, SERVER_USE_COOKIES, SERVER_CHANGE_SESSID,
                CLIENT_USE_HTTP_10);
    }


    @Test
    public void testSelectedMethods() throws Exception {

        FormAuthClientSelectedMethods client =
                new FormAuthClientSelectedMethods(true, true, true, true);

        // First request for protected resource gets the login page
        client.doResourceRequest("PUT", true, "/test?" +
                SelectedMethodsServlet.PARAM + "=" +
                SelectedMethodsServlet.VALUE, null);
        Assert.assertTrue(client.getResponseLine(), client.isResponse200());
        Assert.assertTrue(client.isResponseBodyOK());
        String originalSessionId = client.getSessionId();
        client.reset();

        // Second request replies to the login challenge
        client.doResourceRequest("POST", true, "/test/j_security_check",
                FormAuthClientBase.LOGIN_REPLY);
        Assert.assertTrue("login failed " + client.getResponseLine(),
                client.isResponse303());
        Assert.assertTrue(client.isResponseBodyOK());
        String redirectUri = client.getRedirectUri();
        client.reset();

        // Third request - the login was successful so
        // follow the redirect to the protected resource
        client.doResourceRequest("GET", true, redirectUri, null);
        Assert.assertTrue(client.isResponse200());
        Assert.assertTrue(client.isResponseBodyOK());
        String newSessionId = client.getSessionId();

        Assert.assertTrue(!originalSessionId.equals(newSessionId));
        client.reset();
    }


    /*
     * Test to ensure that the expire and date headers use a GMT date.
     */
    @Test
    public void testDateAndExpireHeadersUseGMT() throws Exception {
        Tomcat tomcat = getTomcatInstance();

        File appDir = new File(getBuildDirectory(), "webapps/examples");
        Context ctxt  = tomcat.addWebapp(null, "/examples", appDir.getAbsolutePath());
        FormAuthenticator form = new FormAuthenticator();
        form.setSecurePagesWithPragma(true);
        ctxt.getPipeline().addValve(form);
        tomcat.start();

        Map<String,List<String>> responseHeaders = new HashMap<>();
        ByteChunk bc = new ByteChunk();
        String path = "http://localhost:" + getPort() + "/examples/jsp/security/protected/index.jsp";
        int rc = getUrl(path, bc, responseHeaders);
        Assert.assertTrue(String.format("Expecting 200, but got ", Integer.valueOf(rc)), rc == 200);
        String expiresDate = responseHeaders.get("Expires").get(0).toString();

        String ExpectedDateFormatRegx = "^[A-za-z]{3}, \\d{2} \\w{3} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT$";
        Pattern pattern = Pattern.compile(ExpectedDateFormatRegx);
        Matcher matcher = pattern.matcher(expiresDate);
        Assert.assertTrue("Expires header date not in expected format", matcher.matches());

        String Date = responseHeaders.get("Date").get(0).toString();
        matcher = pattern.matcher(Date);
        Assert.assertTrue("Date header not in expected format", matcher.matches());
    }


    /*
     * Choreograph the steps of the test dialogue with the server
     *  1. while not authenticated, try to access a protected resource
     *  2. respond to the login challenge with good credentials
     *  3. after successful login, follow the redirect to the original page
     *  4. repeatedly access the protected resource to demonstrate
     *     persistence of the authenticated session
     *
     * @param resourceMethod HTTP method for accessing the protected resource
     * @param redirectMethod HTTP method for the login FORM reply
     * @param useContinue whether the HTTP client should expect a 100 Continue
     * @param clientShouldUseCookies whether the client should send cookies
     * @param serverWillUseCookies whether the server should send cookies
     *
     */
    private String doTest(String resourceMethod, String redirectMethod,
            boolean useContinue, boolean clientShouldUseCookies,
            boolean serverWillUseCookies, boolean serverWillChangeSessid)
            throws Exception {
        return doTest(resourceMethod, redirectMethod, useContinue,
                clientShouldUseCookies, serverWillUseCookies,
                serverWillChangeSessid, true);
    }

    private String doTest(String resourceMethod, String redirectMethod,
            boolean useContinue, boolean clientShouldUseCookies,
            boolean serverWillUseCookies, boolean serverWillChangeSessid,
            boolean clientShouldUseHttp11) throws Exception {

        client = new FormAuthClient(clientShouldUseCookies,
                clientShouldUseHttp11, serverWillUseCookies,
                serverWillChangeSessid);

        // First request for protected resource gets the login page
        client.setUseContinue(useContinue);
        client.doResourceRequest(resourceMethod, false, null, null);
        Assert.assertTrue(client.isResponse200());
        Assert.assertTrue(client.isResponseBodyOK());
        String loginUri = client.extractBodyUri(
                FormAuthClient.LOGIN_PARAM_TAG,
                FormAuthClient.LOGIN_RESOURCE);
        String originalSessionId = null;
        if (serverWillUseCookies && clientShouldUseCookies) {
            originalSessionId = client.getSessionId();
        } else {
            originalSessionId = client.extractPathSessionId(loginUri);
        }
        client.reset();

        // Second request replies to the login challenge
        client.setUseContinue(useContinue);
        client.doLoginRequest(loginUri);
        if (clientShouldUseHttp11) {
            Assert.assertTrue("login failed " + client.getResponseLine(),
                    client.isResponse303());
        } else {
            Assert.assertTrue("login failed " + client.getResponseLine(),
                    client.isResponse302());
        }
        Assert.assertTrue(client.isResponseBodyOK());
        String redirectUri = client.getRedirectUri();
        client.reset();

        // Third request - the login was successful so
        // follow the redirect to the protected resource
        client.doResourceRequest(redirectMethod, true, redirectUri, null);
        if ("POST".equals(redirectMethod)) {
            client.setUseContinue(useContinue);
        }
        Assert.assertTrue(client.isResponse200());
        Assert.assertTrue(client.isResponseBodyOK());
        String protectedUri = client.extractBodyUri(
                FormAuthClient.RESOURCE_PARAM_TAG,
                FormAuthClient.PROTECTED_RESOURCE);
        String newSessionId = null;
        if (serverWillUseCookies && clientShouldUseCookies) {
            newSessionId = client.getSessionId();
        } else {
            newSessionId = client.extractPathSessionId(protectedUri);
        }
        boolean sessionIdIsChanged = !(originalSessionId.equals(newSessionId));
        Assert.assertTrue(sessionIdIsChanged == serverWillChangeSessid);
        client.reset();

        // Subsequent requests - keep accessing the protected resource
        doTestProtected(resourceMethod, protectedUri, useContinue,
                FormAuthClient.LOGIN_SUCCESSFUL, 5);

        return protectedUri;        // in case more requests will be issued
    }

    /*
     * Repeatedly access the protected resource after the client has
     * successfully logged-in to the webapp. The current session attributes
     * will be used and cannot be changed.
     *  3. after successful login, follow the redirect to the original page
     *  4. repeatedly access the protected resource to demonstrate
     *     persistence of the authenticated session
     *
     * @param resourceMethod HTTP method for accessing the protected resource
     * @param protectedUri to access (with or without sessionid)
     * @param useContinue whether the HTTP client should expect a 100 Continue
     * @param clientShouldUseCookies whether the client should send cookies
     * @param serverWillUseCookies whether the server should send cookies
     *
     */
    private void doTestProtected(String resourceMethod, String protectedUri,
            boolean useContinue, int phase, int repeatCount)
            throws Exception {

        // Subsequent requests - keep accessing the protected resource
        for (int i = 0; i < repeatCount; i++) {
            client.setUseContinue(useContinue);
            client.doResourceRequest(resourceMethod, false, protectedUri, null);
            Assert.assertTrue(client.isResponse200());
            Assert.assertTrue(client.isResponseBodyOK(phase));
            client.reset();
        }
    }

    /*
     * Encapsulate the logic needed to run a suitably-configured tomcat
     * instance, send it an HTTP request and process the server response
     */
    private abstract static class FormAuthClientBase extends SimpleHttpClient {

        protected static final String LOGIN_PARAM_TAG = "action=";
        protected static final String LOGIN_RESOURCE = "j_security_check";
        protected static final String LOGIN_REPLY =
                "j_username=tomcat&j_password=tomcat";

        protected static final String PROTECTED_RELATIVE_PATH =
                "/examples/jsp/security/protected/";
        protected static final String PROTECTED_RESOURCE = "index.jsp";
        private static final String PROTECTED_RESOURCE_URL =
                PROTECTED_RELATIVE_PATH + PROTECTED_RESOURCE;
        protected static final String RESOURCE_PARAM_TAG = "href=";
        private static final char PARAM_DELIM = '?';

        // primitive tracking of the test phases to verify the HTML body
        protected static final int LOGIN_REQUIRED = 1;
        protected static final int REDIRECTING = 2;
        protected static final int LOGIN_SUCCESSFUL = 3;
        private int requestCount = 0;

        // todo: forgot this change and making it up again!
        protected final String SESSION_PARAMETER_START =
            SESSION_PARAMETER_NAME + "=";

        protected boolean clientShouldUseHttp11;

        protected void doLoginRequest(String loginUri) throws Exception {

            doResourceRequest("POST", true,
                    PROTECTED_RELATIVE_PATH + loginUri, LOGIN_REPLY);
        }

        /*
         * Prepare the resource request HTTP headers and issue the request.
         * Three kinds of uri are supported:
         *   1. fully qualified uri.
         *   2. minimal uri without webapp path.
         *   3. null - use the default protected resource
         * Cookies are sent if available and supported by the test. Otherwise, the
         * caller is expected to have provided a session id as a path parameter.
         */
        protected void doResourceRequest(String method, boolean isFullQualUri,
                String resourceUri, String requestTail) throws Exception {

            // build the HTTP request while assembling the uri
            StringBuilder requestHead = new StringBuilder(128);
            requestHead.append(method).append(' ');
            if (isFullQualUri) {
                requestHead.append(resourceUri);
            } else {
                if (resourceUri == null) {
                    // the default relative url
                    requestHead.append(PROTECTED_RESOURCE_URL);
                } else {
                    requestHead.append(PROTECTED_RELATIVE_PATH)
                            .append(resourceUri);
                }
                if ("GET".equals(method)) {
                    requestHead.append("?role=bar");
                }
            }
            if (clientShouldUseHttp11) {
                requestHead.append(" HTTP/1.1").append(CRLF);
            } else {
                requestHead.append(" HTTP/1.0").append(CRLF);
            }

            // next, add the constant http headers
            requestHead.append("Host: localhost").append(CRLF);
            requestHead.append("Connection: close").append(CRLF);

            // then any optional http headers
            if (getUseContinue()) {
                requestHead.append("Expect: 100-continue").append(CRLF);
            }
            if (getUseCookies()) {
                String sessionId = getSessionId();
                if (sessionId != null) {
                    requestHead.append("Cookie: ")
                            .append(SESSION_COOKIE_NAME)
                            .append('=').append(sessionId).append(CRLF);
                }
            }

            // finally, for posts only, deal with the request content
            if ("POST".equals(method)) {
                if (requestTail == null) {
                    requestTail = "role=bar";
                }
                requestHead.append(SimpleHttpClient.HTTP_HEADER_CONTENT_TYPE_FORM_URL_ENCODING);
                // calculate post data length
                String len = Integer.toString(requestTail.length());
                requestHead.append("Content-length: ").append(len).append(CRLF);
            }

            // always put an empty line after the headers
            requestHead.append(CRLF);

            String request[] = new String[2];
            request[0] = requestHead.toString();
            request[1] = requestTail;
            doRequest(request);
        }

        private void doRequest(String request[]) throws Exception {
            setRequest(request);
            connect();
            processRequest();
            disconnect();
            requestCount++;
        }

        /*
         * verify the server response HTML body is the page we expect,
         * based on the dialogue position within doTest.
         */
        @Override
        public boolean isResponseBodyOK() {
            return isResponseBodyOK(requestCount);
        }

        /*
         * verify the server response HTML body is the page we expect,
         * based on the dialogue position given by the caller.
         */
        public boolean isResponseBodyOK(int testPhase) {
            switch (testPhase) {
                case LOGIN_REQUIRED:
                    // First request should return in the login page
                    assertContains(getResponseBody(),
                            "<title>Login Page for Examples</title>");
                    return true;
                case REDIRECTING:
                    // Second request should result in redirect without a body
                    return true;
                default:
                    // Subsequent requests should return in the protected page.
                    // Our role parameter should be appear in the page.
                    String body = getResponseBody();
                    assertContains(body,
                            "<title>Protected Page for Examples</title>");
                    assertContains(body,
                            "<input type=\"text\" name=\"role\" value=\"bar\"");
                    return true;
            }
        }

        /*
         * Scan the server response body and extract the given
         * url, including any path elements.
         */
        protected String extractBodyUri(String paramTag, String resource) {
            extractUriElements();
            List<String> elements = getResponseBodyUriElements();
            String fullPath = null;
            for (String element : elements) {
                int ix = element.indexOf(paramTag);
                if (ix > -1) {
                    ix += paramTag.length();
                    char delim = element.charAt(ix);
                    int iy = element.indexOf(resource, ix);
                    if (iy > -1) {
                        int lastCharIx = element.indexOf(delim, iy);
                        fullPath = element.substring(iy, lastCharIx);
                        // remove any trailing parameters
                        int paramDelim = fullPath.indexOf(PARAM_DELIM);
                        if (paramDelim > -1) {
                            fullPath = fullPath.substring(0, paramDelim);
                        }
                        break;
                    }
                }
            }
            return fullPath;
        }

        /*
         * extract the session id path element (if it exists in the given url)
         */
        protected String extractPathSessionId(String url) {
            String sessionId = null;
            int iStart = url.indexOf(SESSION_PARAMETER_START);
            if (iStart > -1) {
                iStart += SESSION_PARAMETER_START.length();
                String remainder = url.substring(iStart);
                StringTokenizer parser = new StringTokenizer(remainder,
                        SESSION_PATH_PARAMETER_TAILS);
                if (parser.hasMoreElements()) {
                    sessionId = parser.nextToken();
                } else {
                    sessionId = url.substring(iStart);
                }
            }
            return sessionId;
        }

        private void assertContains(String body, String expected) {
            if (!body.contains(expected)) {
                Assert.fail("Response number " + requestCount
                        + ": body check failure.\n"
                        + "Expected to contain substring: [" + expected
                        + "]\nActual: [" + body + "]");
            }
        }
    }


    private class FormAuthClient extends FormAuthClientBase {
        private FormAuthClient(boolean clientShouldUseCookies,
                boolean clientShouldUseHttp11,
                boolean serverShouldUseCookies,
                boolean serverShouldChangeSessid) throws Exception {

            this.clientShouldUseHttp11 = clientShouldUseHttp11;

            Tomcat tomcat = getTomcatInstance();
            File appDir = new File(System.getProperty("tomcat.test.basedir"), "webapps/examples");
            Context ctx = tomcat.addWebapp(null, "/examples",
                    appDir.getAbsolutePath());
            setUseCookies(clientShouldUseCookies);
            ctx.setCookies(serverShouldUseCookies);
            ctx.addApplicationListener(WsContextListener.class.getName());

            TesterMapRealm realm = new TesterMapRealm();
            realm.addUser("tomcat", "tomcat");
            realm.addUserRole("tomcat", "tomcat");
            ctx.setRealm(realm);

            tomcat.start();

            // Valve pipeline is only established after tomcat starts
            Valve[] valves = ctx.getPipeline().getValves();
            for (Valve valve : valves) {
                if (valve instanceof AuthenticatorBase) {
                    ((AuthenticatorBase)valve)
                            .setChangeSessionIdOnAuthentication(
                                                serverShouldChangeSessid);
                    break;
                }
            }

            // Port only known after Tomcat starts
            setPort(getPort());
        }
    }


    /**
     * Encapsulate the logic needed to run a suitably-configured Tomcat
     * instance, send it an HTTP request and process the server response when
     * the protected resource is only protected for some HTTP methods. The use
     * case of particular interest is when GET and POST are not protected since
     * those are the methods used by the login form and the redirect and if
     * those methods are not protected the authenticator may not process the
     * associated requests.
     */
    private class FormAuthClientSelectedMethods extends FormAuthClientBase {

        private FormAuthClientSelectedMethods(boolean clientShouldUseCookies,
                boolean clientShouldUseHttp11,
                boolean serverShouldUseCookies,
                boolean serverShouldChangeSessid) throws Exception {

            this.clientShouldUseHttp11 = clientShouldUseHttp11;

            Tomcat tomcat = getTomcatInstance();

            Context ctx = tomcat.addContext(
                    "", System.getProperty("java.io.tmpdir"));
            Tomcat.addServlet(ctx, "SelectedMethods",
                    new SelectedMethodsServlet());
            ctx.addServletMappingDecoded("/test", "SelectedMethods");
            // Login servlet just needs to respond "OK". Client will handle
            // creating a valid response. No need for a form.
            Tomcat.addServlet(ctx, "Login",
                    new TesterServlet());
            ctx.addServletMappingDecoded("/login", "Login");

            // Configure the security constraints
            SecurityConstraint constraint = new SecurityConstraint();
            SecurityCollection collection = new SecurityCollection();
            collection.setName("Protect PUT");
            collection.addMethod("PUT");
            collection.addPatternDecoded("/test");
            constraint.addCollection(collection);
            constraint.addAuthRole("tomcat");
            ctx.addConstraint(constraint);

            // Configure authentication
            LoginConfig lc = new LoginConfig();
            lc.setAuthMethod("FORM");
            lc.setLoginPage("/login");
            ctx.setLoginConfig(lc);
            ctx.getPipeline().addValve(new FormAuthenticator());

            setUseCookies(clientShouldUseCookies);
            ctx.setCookies(serverShouldUseCookies);

            TesterMapRealm realm = new TesterMapRealm();
            realm.addUser("tomcat", "tomcat");
            realm.addUserRole("tomcat", "tomcat");
            ctx.setRealm(realm);

            tomcat.start();

            // Valve pipeline is only established after tomcat starts
            Valve[] valves = ctx.getPipeline().getValves();
            for (Valve valve : valves) {
                if (valve instanceof AuthenticatorBase) {
                    ((AuthenticatorBase)valve)
                            .setChangeSessionIdOnAuthentication(
                                                serverShouldChangeSessid);
                    break;
                }
            }

            // Port only known after Tomcat starts
            setPort(getPort());
        }

        @Override
        public boolean isResponseBodyOK() {
            if (isResponse303()) {
                return true;
            }
            Assert.assertTrue(getResponseBody(), getResponseBody().contains("OK"));
            Assert.assertFalse(getResponseBody().contains("FAIL"));
            return true;
        }
    }


    private static final class SelectedMethodsServlet extends HttpServlet {

        private static final long serialVersionUID = 1L;
        public static final String PARAM = "TestParam";
        public static final String VALUE = "TestValue";

        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            resp.setContentType("text/plain;charset=UTF-8");

            if (VALUE.equals(req.getParameter(PARAM)) &&
                    req.isUserInRole("tomcat")) {
                resp.getWriter().print("OK");
            } else {
                resp.getWriter().print("FAIL");
            }
        }

        @Override
        protected void doPost(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Same as GET for this test case
            doGet(req, resp);
        }

        @Override
        protected void doPut(HttpServletRequest req, HttpServletResponse resp)
                throws ServletException, IOException {
            // Same as GET for this test case
            doGet(req, resp);
        }
    }
}
